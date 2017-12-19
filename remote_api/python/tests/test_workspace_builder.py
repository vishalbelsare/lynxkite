import unittest
import lynx.kite
import json


class TestWorkspaceBuilder(unittest.TestCase):

  def test_one_box_ws(self):
    lk = lynx.kite.LynxKite()
    # Using explicit output name for test.
    state = lk.get_state_id(lk.createExampleGraph()['project'])
    project = lk.get_project(state)
    scalars = {s.title: lk.get_scalar(s.id) for s in project.scalars}
    self.assertEqual(scalars['!vertex_count'].double, 4.0)
    self.assertEqual(scalars['!edge_count'].double, 4.0)
    self.assertEqual(scalars['greeting'].string, 'Hello world! 😀 ')

  def test_simple_chain(self):
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().computePagerank().sql1(sql='select page_rank from vertices')
    table_state = lk.get_state_id(state)
    table = lk.get_table(table_state)
    self.assertEqual(table.header[0].dataType, 'Double')
    self.assertEqual(table.header[0].name, 'page_rank')
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['1.80917', '1.80917', '0.19083', '0.19083'])

  def test_simple_sql_chain(self):
    lk = lynx.kite.LynxKite()
    state = (lk.createExampleGraph()
             .sql1(sql='select * from vertices where age < 30')
             .sql1(sql='select name from input where age > 2'))
    table_state = lk.get_state_id(state)
    table = lk.get_table(table_state)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Adam', 'Eve'])

  def test_multi_input(self):
    lk = lynx.kite.LynxKite()
    eg = lk.createExampleGraph()
    new_edges = eg.sql1(sql='select * from edges where edge_weight > 1')
    new_graph = lk.useTableAsEdges(
        eg, new_edges, attr='id', src='src_id', dst='dst_id')
    project = lk.get_project(lk.get_state_id(new_graph))
    scalars = {s.title: lk.get_scalar(s.id) for s in project.scalars}
    self.assertEqual(scalars['!vertex_count'].double, 4.0)
    self.assertEqual(scalars['!edge_count'].double, 3.0)

  def test_pedestrian_custom_box(self):
    lk = lynx.kite.LynxKite()
    i = lk.input(name='graph')
    o = i.sql1(sql='select name from vertices').output(name='vtable')
    ws = lynx.kite.Workspace('allvs', [o], [i])
    table_state = lk.get_state_id(ws(lk.createExampleGraph()))
    table = lk.get_table(table_state)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Adam', 'Eve', 'Bob', 'Isolated Joe'])

  def test_parametric_parameters(self):
    from lynx.kite import pp
    lk = lynx.kite.LynxKite()
    state = lk.createExampleGraph().sql1(
        sql=pp('select name from `vertices` where age = $ap'))
    state_id = lk.get_state_id(state, ws_parameters={'ap': '18.2'})
    table = lk.get_table(state_id)
    values = [row[0].string for row in table.data]
    self.assertEqual(values, ['Eve'])

  def test_wrong_chain_with_multiple_inputs(self):
    lk = lynx.kite.LynxKite()
    with self.assertRaises(Exception) as context:
      state = lk.createExampleGraph().sql2(sql='select * from vertices')
    self.assertTrue('sql2 has more than one input' in str(context.exception))
