'use strict';

// Viewer of a state at an output of a box.

angular.module('biggraph')
 .directive('stateView', function(side, util) {
    return {
      restrict: 'E',
      templateUrl: 'scripts/workspace/state-view.html',
      scope: {
        state: '='
      },
      link: function(scope) {
        scope.sides = [];

        util.deepWatch(scope, 'state', function() {
          scope.sides = [];
          scope.left = new side.Side(scope.sides, 'left', scope.state);
          scope.right = new side.Side(scope.sides, 'right', scope.state);
          scope.sides.push(scope.left);
          scope.sides.push(scope.right);

          scope.sides[0].state.projectPath = '';
          scope.sides[0].reload();
        });

        scope.$watch(
          'left.project.$resolved',
          function(loaded) {
            if (loaded) {
              scope.left.onProjectLoaded();
            }
          });
        scope.$watch(
          'right.project.$resolved',
          function(loaded) {
            if (loaded) {
              scope.right.onProjectLoaded();
            }
          });
    }
  };
});
