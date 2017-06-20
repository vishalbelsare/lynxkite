'use strict';

module.exports = function() {};

var lib = require('../test-lib.js');

module.exports = function(fw) {

/* TODO: Revive this: #6153


  fw.statePreservingTest(
    'test-example project with example graph',
    'SQL table browser in project',
    function() {
      // Create a segmentation.
      lib.workspace.addBox({
        id: 'sda1', name: 'Segment by Double attribute',
        x: 100, y: 200, after: 'eg0',
        params: {
          'attr': 'age',
          'interval_size': '1',
          'name': 'bucketing'
        }
      });

      var tableBrowser = lib.left.tableBrowser;
      // Open table browser.
      tableBrowser.toggle();
      // Check top-level node list.
      tableBrowser.expectNode([0], 'edges', '`edges`');
      tableBrowser.expectNode(
          [1], 'edge_attributes', '`edge_attributes`');
      tableBrowser.expectNode([2], 'vertices', '`vertices`');
      tableBrowser.expectNode([3], 'bucketing', '`bucketing`');

      // Check first few subitems of the vertices node.
      tableBrowser.toggleNode([2]);
      tableBrowser.expectNode([2, 0], '*ALL*');
      tableBrowser.expectNode([2, 1], 'name (String)', '`name`');
      tableBrowser.toggleNode([2]);
      lib.expectNotElement(tableBrowser.getNode([0, 0]));

      // Open the first subnode of the fourth node.
      tableBrowser.toggleNode([3]); // 'bucketing'
      tableBrowser.toggleNode([3, 1]); // 'bucketing|vertices'
      tableBrowser.expectNode([3, 1, 1], 'id (ID)', '`id`');
      tableBrowser.expectNode(
          [3, 1, 0],
          '*ALL*',
          '`id`,\n`size`,\n`bottom`,\n`top`');
      // Test with fully qialified names as well.
      tableBrowser.toggleFullyQualify();
      tableBrowser.expectNode(
          [3, 1, 1],
          'id (ID)',
          '`bucketing|vertices`.`id`');
      tableBrowser.expectNode(
          [3, 1, 0],
          '*ALL*',
          '`bucketing|vertices`.`id`,\n' +
              '`bucketing|vertices`.`size`,\n' +
              '`bucketing|vertices`.`bottom`,\n`' +
              'bucketing|vertices`.`top`');
      tableBrowser.toggleFullyQualify();
      tableBrowser.expectNode([3, 1, 1], 'id (ID)', '`id`');

      // Close table browser.
      tableBrowser.toggle();
      lib.expectNotElement(lib.left.side.$('#table-browser'));
    });
*/

  fw.transitionTest(
    'snapshot created',
    'SQL table browser - global SQL box',
    function() {
      lib.splash.newDirectory('plum');
      lib.splash.popDirectory();
      lib.splash.openGlobalSqlBox();
      var tableBrowser = lib.splash.tableBrowser;
      tableBrowser.expectNode([0], 'plum', '`plum`');
      tableBrowser.expectNode([1], 'This is a snapshot.', '`This is a snapshot.`');
      tableBrowser.toggleNode([1]);
      tableBrowser.expectNode([1, 0], 'vertices', '`This is a snapshot.|vertices`');
      tableBrowser.enterSearchQuery('snap');
      tableBrowser.expectNode([0], 'This is a snapshot.', '`This is a snapshot.`');
      tableBrowser.toggle();
    },
    function() {
    });
};

