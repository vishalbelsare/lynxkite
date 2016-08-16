#!/usr/bin/python3
'''
Command-line utility to spin up an EMR cluster with an RDS
database, and run Luigi task based performance tests on it.
'''
#!/usr/bin/python3

import argparse
import boto3
import os
import time
from utils.emr_lib import EMRLib

parser = argparse.ArgumentParser()
parser.add_argument(
    '--cluster_name',
    default=os.environ['USER'] + '-ecosystem-test',
    help='Name of the cluster to start')
parser.add_argument(
    '--biggraph_releases_dir',
    default=os.environ['HOME'] + '/biggraph_releases',
    help='''Directory containing the downloader script, typically the root of
         the biggraph_releases repo. The downloader script will have the form of
         BIGGRAPH_RELEASES_DIR/download-lynx-LYNX_VERSION.sh''')
parser.add_argument(
    '--lynx_version',
    default='1.9.1',
    help='''Version of the ecosystem release to test. A downloader script of the
          following form will be used for obtaining the release:
         BIGGRAPH_RELEASES_DIR/download-lynx-LYNX_VERSION.sh''')
parser.add_argument(
    '--task_module',
    default='test_tasks.jdbc')
parser.add_argument(
    '--task',
    default='JDBCTest1k')
parser.add_argument(
    '--ec2_key_file',
    default=os.environ['HOME'] + '/.ssh/lynx-cli.pem')
parser.add_argument(
    '--ec2_key_name',
    default='lynx-cli')
parser.add_argument(
    '--emr_instance_count',
    default=3)
parser.add_argument(
    '--results_dir',
    default='./ecosystem/tests/results/')
parser.add_argument(
    '--rm',
    action='store_true',
    help='''Delete the cluster after completion.''')


def main(args):
  # Create an EMR cluster and a MySQL database in RDS.
  lib = EMRLib(
      ec2_key_file=args.ec2_key_file,
      ec2_key_name=args.ec2_key_name)
  cluster = lib.create_or_connect_to_emr_cluster(
      name=args.cluster_name,
      instance_count=args.emr_instance_count)
  mysql_instance = lib.create_or_connect_to_rds_instance(
      name=args.cluster_name + '-mysql')
  # Wait for startup of both.
  lib.wait_for_services([cluster, mysql_instance])

  mysql_address = mysql_instance.get_address()
  jdbc_url = 'jdbc:mysql://{mysql_address!s}/db?user=root&password=rootroot'.format(
      mysql_address=mysql_address)

  # Upload installer script.
  cluster.rsync_up(
      src='{dir!s}/download-lynx-{version!s}.sh'.format(
          dir=args.biggraph_releases_dir,
          version=args.lynx_version),
      dst='/mnt/')

  # Upload tasks.
  ecosystem_task_dir = '/mnt/lynx-' + args.lynx_version + '/lynx/luigi_tasks/test_tasks'
  cluster.ssh('mkdir -p ' + ecosystem_task_dir)
  cluster.rsync_up('ecosystem/tests/', ecosystem_task_dir)

  install_docker_and_lynx(cluster, args.lynx_version)
  start_or_reset_ecosystem(cluster, args.lynx_version)
  start_tests(cluster, jdbc_url)
  print('Tests are now running in the background. Waiting for results.')
  output = fetch_output(cluster)
  save_output(output)
  shut_down_instances(cluster, mysql_instance)


def install_docker_and_lynx(cluster, version):
  cluster.ssh('''
    set -x
    cd /mnt

    if [ ! -f "./lynx-{version!s}/lynx/start.sh" ]; then
      # Not yet installed.
      ./download-lynx-{version!s}.sh
      tar xfz lynx-{version!s}.tgz
      # Temporary fix over 1.9.0-hotfix1
      pushd lynx-{version!s}/lynx
      chmod a+x *.sh
      popd
    fi

    cd lynx-{version!s}

    if ! hash docker 2>/dev/null; then
      echo "docker not found, installing"
      sudo LYNXKITE_USER=hadoop ./install-docker.sh
    fi

    # no need for scheduled tasks today
    echo "sleep_period_seconds: 1000000000\nscheduling: []" >lynx/luigi_tasks/chronomaster.yml

    # Log out here to refresh docker user group.
  '''.format(version=version))


def start_or_reset_ecosystem(cluster, version):
  kite_config = '''
    KITE_INSTANCE: ecosystem-test
    KITE_DATA_DIR: hdfs://\$HOSTNAME:8020/user/\$USER/lynxkite_data/
    KITE_MASTER_MEMORY_MB: 8000
    NUM_EXECUTORS: {num_executors!s}
    EXECUTOR_MEMORY: 18g
    NUM_CORES_PER_EXECUTOR: 8
'''.format(num_executors=args.emr_instance_count - 1)

  res = cluster.ssh('''
    cd /mnt/lynx-{version!s}/lynx
    # Start ecosystem.
    if [[ $(docker ps -qf name=lynx_luigi_worker_1) ]]; then
      echo "Ecosystem is running. Cleanup and Luigi restart is needed."
      hadoop fs -rm -r /user/hadoop/lynxkite_data
      sudo rm -Rf ./lynxkite_metadata/*
      ./reload_luigi_tasks.sh
    else
      # Update configuration:
      sed -i.bak '/Please configure/q' docker-compose-lynxkite.yml
      cat >>docker-compose-lynxkite.yml <<EOF{kite_config!s}EOF
      ./start.sh
    fi

    # Wait for ecosystem startup completion.
    # (We keep retrying a dummy task until it succeeds.)
    docker exec lynx_luigi_worker_1 rm -f /tasks_data/smoke_test_marker.txt
    docker exec lynx_luigi_worker_1 rm -Rf /tmp/luigi/
    while [[ $(docker exec lynx_luigi_worker_1 cat /tasks_data/smoke_test_marker.txt) != "done" ]]; do
      docker exec lynx_luigi_worker_1 luigi --module test_tasks.smoke_test SmokeTest 2>/dev/null
      sleep 1
    done'''.format(
      version=args.lynx_version,
      kite_config=kite_config))


def start_tests(cluster, jdbc_url):
  '''Start running the tests in the background.'''
  cluster.ssh('''
    cat >/home/hadoop/run_test.sh <<EOF
      echo "START TIME: $(date) $(date +%s)"
      docker exec lynx_luigi_worker_1 luigi --module {luigi_module!s} {luigi_task!s} --jdbc-url '{jdbc_url!s}'
      echo "END TIME: ($date) $(date +%s)"
      echo 'done' >/home/hadoop/test_status.txt
    echo
EOF
    chmod a+x /home/hadoop/run_test.sh
    rm -f /home/hadoop/test_status.txt
    nohup /home/hadoop/run_test.sh >/home/hadoop/test_results.txt 2>&1 &
  '''.format(
      jdbc_url=jdbc_url,
      luigi_module=args.task_module,
      luigi_task=args.task))


def fetch_output(cluster):
  '''Periodically connects to the master and downloads and prints
  the output log of the running experiment. Also monitors a status
  file at the master, and quits the loop in case of done status.
  It would be simpler to have a continuous ssh connection to the
  master, but that breaks if the Internet connection flakes.'''
  all_output = ''
  output_lines_seen = 0
  status_is_done = False
  while not status_is_done:
    # Check status.
    status, ssh_retcode = cluster.ssh(
        'cat /home/hadoop/test_status.txt 2>/dev/null',
        print_output=False,
        verbose=False)
    status_is_done = ssh_retcode == 0 and 'done' == status.strip()
    # Print unseen log lines.
    output_results, return_code = cluster.ssh(
        'tail -n +{offset!s} /home/hadoop/test_results.txt'.format(
            offset=output_lines_seen + 1),
        verbose=False,
        print_output=False)
    if return_code == 0:
      # We only use the output of ssh if it was successful. Otherwise we'll
      # try again with the same offset in the next round.
      print(output_results, end='')
      all_output += output_results
      output_lines_seen += output_results.count('\n')
    time.sleep(5)
  return all_output


def save_output(output):
  if not args.results_dir:
    return

  output_lines = []
  for line in output.split('\n'):
    if line.startswith('[time='):
      output_lines.append(line)

  if not os.path.exists(args.results_dir):
    os.makedirs(args.results_dir)
  with open(args.results_dir + '/result.txt', 'w') as f:
    f.write('\n'.join(output_lines))
    f.write('\n')


def prompt_delete():
  if args.rm:
    return True
  print('Terminate instances? [y/N] ', end='')
  choice = input().lower()
  if choice != 'y':
    print('''Please don't forget to terminate the instances!''')
    return


def shut_down_instances(cluster, db):
  if prompt_delete():
    print('Shutting down instances.')
    cluster.terminate()
    db.terminate()


if __name__ == '__main__':
  args = parser.parse_args()
  main(args)