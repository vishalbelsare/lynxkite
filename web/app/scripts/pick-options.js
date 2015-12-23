// The component responsible for center selection.
//
// The state of the picker is scattered around a few places. The state needs to be persisted
// for two reasons: page reloads, visualizations save/load.
//  scope.count   (persisted via scope.side.state.lastCentersRequest, see scope.reset())
//    - UI-bound value of number of centers requested
//  scope.filters   (persisted via scope.side.state.lastCentersRequest, see scope.reset())
//    - UI-bound value for filters.
//  scope.side.state.customVisualizationFilters  (persisted via state, see UIStatus.scala)
//    - UI-bound value of the toggle switch between custom or project restrictions.
//
//  scope.side.pickOptions.offset  (not persisted)
//    - Number of times "Pick"/"Next" button was pushed without changing
//      parameters.
//  scope.side.pickOptions.lastCenterRequestParameters   (not persisted)
//    - last pick request generated from the UI. This is only used to detect
//      changes of the parameters by the user on the UI.
//
//  scope.side.state.lastCentersRequest   (persisted via state, see UIStatus.scala)
//    - The last successful centers request
//  scope.side.state.lastCentersResponse   (not persisted)
//    - The response to lastCentersRequest
//  scope.side.state.centers   (persisted via state, see UIStatus.scala)
//    - The centers from lastCentersRequest (or overridden by the user). UI-bound
//
// What happens when the user presses Pick/Next?
// 1. scope.side.pickOptions.lastCenterRequestParameters is updated if there was a change
//    on the UI.
// 2. The params are sent to backend via side.sendCenterRequest(). In case of success,
//    state.centers, state.lastCenterRequest and state.lastCentersResponse is updated.
// 3. This also triggers scope.reset(), but in theory, it should not make any difference in
//    this case.
//
// What happens when loading a visualization?
// 1. side.state.* is updated by the load.
// 2. scope.reset() is triggered and that updates the UI from scope.side.state.lastCenterRequest
//
// What happens on a page reload?
// 1. The DOM tree and angular controls are constructed from zero.
// 2. side.state is restored from the URL
// 3. scope.reset() is triggered and that updates the UI from scope.side.state.lastCenterRequest
//
//
// Have fun!

'use strict';

angular.module('biggraph').directive('pickOptions', function() {
  return {
    scope: { side: '=' },
    templateUrl: 'pick-options.html',
    link: function(scope) {
      scope.count = '1';
      scope.reset = function() {
        scope.filters = [];
        // pickOptions is stored in "side" to survive even when the picker is recreated.
        scope.side.pickOptions = scope.side.pickOptions || {};
        var lastCentersRequest = scope.side.state.lastCentersRequest;
        if (lastCentersRequest) {
          if (lastCentersRequest.filters) {
            scope.filters = lastCentersRequest.filters;
          }
          if (lastCentersRequest.count) {
            scope.count = lastCentersRequest.count.toString();
          }
        }
      };
      scope.reset();

      scope.copyRestrictionsFromFilters = function() {
        scope.filters = scope.side.nonEmptyVertexFilterNames();
      };

      function centerRequestParams() {
        if (scope.side.state.customVisualizationFilters) {
          var filters = scope.filters.filter(function (filter) {
            return filter.attributeName !== '';
          });
          return {
            count: parseInt(scope.count),
            filters: filters };
        } else {
          return {
            count: parseInt(scope.count),
            filters: scope.side.nonEmptyVertexFilterNames() };
        }
      }

      scope.unchanged = function() {
        var resolvedParams = scope.side.resolveCenterRequestParams(centerRequestParams());
        return angular.equals(scope.side.pickOptions.lastCenterRequestParameters, resolvedParams);
      };

      scope.requestNewCenters = function() {
        var params = centerRequestParams();
        if (scope.unchanged()) { // "Next"
          scope.side.pickOptions.offset += params.count;
          params.offset = scope.side.pickOptions.offset;
        } else { // "Pick"
          scope.side.pickOptions = {
            offset: 0,
            lastCenterRequestParameters: scope.side.resolveCenterRequestParams(params),
          };
        }
        scope.side.sendCenterRequest(params);
      };

      scope.addFilter = function() {
        scope.filters.push({
          attributeName: '',
          valueSpec: '',
        });
      };

      scope.removeFilter = function(idx) {
        scope.filters.splice(idx, 1);
      };

      scope.toggleCustomFilters = function(custom) {
        if (custom) {
          scope.copyRestrictionsFromFilters();
        } else {
          scope.filters = [];
        }
      };

      scope.$watch('side.state.lastCentersRequest', function() {
        scope.reset();
      });
      scope.$watch('side.state.graphMode', function() {
        var state = scope.side.state;
        if (state.graphMode === 'sampled' && state.centers === undefined) {
          scope.requestNewCenters();
        }
      });
    },
  };
});
