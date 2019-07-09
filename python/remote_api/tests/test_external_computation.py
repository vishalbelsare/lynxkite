import lynx.kite
import numpy as np
import os
import shutil
import pandas as pd
import unittest
import tempfile


class TestExternalComputation(unittest.TestCase):

  def test_pandas(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def title_names(table):
      df = table.pandas()
      df['titled_name'] = np.where(df.gender == 'Female', 'Ms ' + df.name, 'Mr ' + df.name)
      return df

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = title_names(eg)
    t.trigger()
    self.assertTrue(t.sql('select titled_name from input').df().equals(
        pd.DataFrame({'titled_name': [
            'Mr Adam',
            'Ms Eve',
            'Mr Bob',
            'Mr Isolated Joe',
        ]})))

  def test_big_dataframes(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def receive_and_send(table):
      return table.pandas()

    v = lk.createVertices(size=200100).sql('select * from `vertices`')
    t = receive_and_send(v)
    t.trigger()

  def test_pyspark(self):
    lk = lynx.kite.LynxKite()
    try:
      from pyspark.sql import SparkSession
    except ImportError:
      print('Skipping PySpark test. "pip install pyspark" if you want to run it.')
      return
    spark = SparkSession.builder.appName('test').getOrCreate()

    @lynx.kite.external
    def stats(table):
      df = table.spark(spark)
      return df.groupBy('gender').count()

    eg = lk.createExampleGraph().sql('select * from vertices')
    t = stats(eg)
    t.trigger()
    # Column order can be different
    actual = t.sql('select * from input').df().sort_index(axis=1)
    expected = pd.DataFrame({'gender': ['Female', 'Male'], 'count': [1.0, 3.0]}).sort_index(axis=1)
    self.assertTrue(actual.equals(expected))

  def test_filename(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def title_names(table):
      df = table.pandas()
      df['titled_name'] = np.where(df.gender == 'Female', 'Ms ' + df.name, 'Mr ' + df.name)
      with tempfile.NamedTemporaryFile() as f:
        df.to_parquet(f.name)
        path_lk = lk.upload(f.file.read())
      return path_lk

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = title_names(eg)
    t.trigger()
    self.assertTrue(t.sql('select titled_name from input').df().equals(
        pd.DataFrame({'titled_name': [
            'Mr Adam',
            'Ms Eve',
            'Mr Bob',
            'Mr Isolated Joe',
        ]})))

  def test_multiple_inputs(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def join(t1, t2):
      return t1.pandas().join(t2.pandas())

    eg = lk.createExampleGraph()
    t = join(eg.sql('select age from vertices'), eg.sql('select name from vertices'))
    t.trigger()
    self.assertTrue(t.sql('select age, name from input').df().equals(
        pd.DataFrame({
            'age': [20.3, 18.2, 50.3, 2.0], 'name': ['Adam', 'Eve', 'Bob', 'Isolated Joe']})))

  def test_ordering(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def join(t1, t2):
      return t1.pandas().join(t2.pandas())

    @lynx.kite.subworkspace
    def compute(eg, sec=lynx.kite.SideEffectCollector.AUTO):
      ages = eg.sql('select age from vertices')
      names = eg.sql('select names from vertices')
      ages.saveToSnapshot(path='ages').register(sec)
      t = join(ages, names).register(sec)
      eg.saveToSnapshot(path='names').register(sec)
      return t

    sec = lynx.kite.SideEffectCollector()
    compute(lk.createExampleGraph()).register(sec)
    # The triggerables are in insertion order.
    self.assertEqual([
        'saveToSnapshot', 'exportToParquet', 'exportToParquet', 'externalComputation2',
        'saveToSnapshot'],
        [t.base.operation for t in sec.all_triggerables()])
    deps = lynx.kite.BoxPath.dependencies(list(sec.all_triggerables()))
    # Dependencies are correctly found.
    self.assertEqual({
        'compute_?/join_0/exportToParquet_0': {'compute_?/saveToSnapshot_1'},
        'compute_?/join_0/exportToParquet_1': {'compute_?/saveToSnapshot_1'},
        'compute_?/join_0/externalComputation2_0': {'compute_?/join_0/exportToParquet_0',
                                                    'compute_?/join_0/exportToParquet_1',
                                                    'compute_?/saveToSnapshot_0',
                                                    'compute_?/saveToSnapshot_1'},
        'compute_?/saveToSnapshot_0': {'compute_?/saveToSnapshot_1'},
        'compute_?/saveToSnapshot_1': set(),
    }, {str(k): set(str(v) for v in vs) for (k, vs) in deps.items()})
    # Serialized trigger order puts exporting before the external computation.
    self.assertEqual([
        'compute_?/saveToSnapshot_1',
        'compute_?/saveToSnapshot_0',
        'compute_?/join_0/exportToParquet_0',
        'compute_?/join_0/exportToParquet_1',
        'compute_?/join_0/externalComputation2_0',
    ], [str(bp) for bp in lynx.kite.serialize_deps(deps)])

  def test_lk(self):
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def title_names(table):
      return table.lk().sql('''
          select concat(case when gender = "Female" then "Ms " else "Mr " end, name) as titled_name
          from input''')

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = title_names(eg)
    t.trigger()
    self.assertTrue(t.sql('select titled_name from input').df().equals(
        pd.DataFrame({'titled_name': [
            'Mr Adam',
            'Ms Eve',
            'Mr Bob',
            'Mr Isolated Joe',
        ]})))

  def test_save_as_csv(self):
    lk = lynx.kite.LynxKite()
    local_path = '/tmp/external-box-csv-test.csv'
    local_path_no_header = '/tmp/external-box-csv-test-2.csv'

    @lynx.kite.external
    def females(table):
      table.pandas().to_csv(local_path, index=False)
      df = pd.read_csv(local_path)
      return df[df['gender'] == 'Female']['name'].to_frame()

    @lynx.kite.external
    def no_header(table):
      df = table.pandas()
      df[df['gender'] != 'Male'].to_csv(local_path_no_header, header=False, index=False)
      return df

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = females(eg)
    t.trigger()
    result = t.sql('select * from input').get_table_data().data[0][0].string
    self.assertEqual(result, 'Eve')
    t2 = no_header(eg)
    t2.trigger()
    with open(local_path_no_header, 'r') as f:
      self.assertEqual(f.read(), 'Eve,Female\n')
    # pandas.to_csv is idempotent
    t2.trigger()
    with open(local_path_no_header, 'r') as f:
      self.assertEqual(f.read(), 'Eve,Female\n')


class TestTmpFilesHandling(unittest.TestCase):
  # On Jenkins all jobs are using the same /tmp folder so we are setting the tmp dir used by the
  # tempfile module to be a separate folder.
  tmp_dir = tempfile.gettempdir() + '/external_tests'
  shutil.rmtree(tmp_dir, ignore_errors=True)
  os.makedirs(tmp_dir)
  tempfile.tempdir = tmp_dir

  def num_tmp_files(self):
    return len(os.listdir(self.tmp_dir))

  def test_tempfile_cleanup(self):
    '''Checks that the temp files are cleaned up even if an error occurred during the
    external computation.
    '''
    lk = lynx.kite.LynxKite()

    @lynx.kite.external
    def create_tmp_files(table):
      df = table.pandas()
      # Make sure that we have really created a temp file for downloading table.
      self.assertEqual(self.num_tmp_files(), 1,
                       'The external box should have created one temp file.')
      raise FailedExternal("I'm throwing an error")
      return df

    eg = lk.createExampleGraph().sql('select name, gender from vertices')
    t = create_tmp_files(eg)
    with self.assertRaises(FailedExternal):
      t.trigger()
    self.assertEqual(self.num_tmp_files(), 0)


class FailedExternal(Exception):
  pass
