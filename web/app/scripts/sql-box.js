// Presents the parameters for running SQL scripts.
'use strict';

angular.module('biggraph').directive('sqlBox', function($rootScope, $window, side, util) {
  return {
    restrict: 'E',
    scope: {
      side: '=?',
      directory: '=?',
     },
    templateUrl: 'sql-box.html',
    link: function(scope) {
      scope.inProgress = 0;
      scope.directoryDefined = (typeof scope.directory !== 'undefined');
      scope.maxRows = 10;
      scope.maxPersistedHistoryLength = 100;

      if(!!scope.side && scope.directoryDefined) {
        throw 'can not be both defined: scope.side, scope.directory';
      }
      if(!scope.side && !scope.directoryDefined) {
        throw 'one of them needs to be defined: scope.side, scope.directory';
      }
      scope.isGlobal = !scope.side;
      scope.sql = scope.isGlobal ? 'select * from `directory/project|vertices`' :
       'select * from vertices';

      function SqlHistory(maxLength) {
        // Load persisted sql history
        this.load = function() {
          try {
            this.history = angular.fromJson(window.localStorage.getItem('sqlHistory'));
            if (!Array.isArray(this.history)) {
              throw 'sqlHistory is not an array';
            }
          } catch(e) {
            this.history = [];
            window.localStorage.setItem('sqlHistory', angular.toJson([]));
          }
          // Store current query as first element
          this.history.unshift(scope.sql);
          this.index = 0;
        };

        // Initialize
        this.maxLength = maxLength;
        this.history = [];
        this.load();

        // Save current query
        this.save = function() {
          this.index = 0;
          this.history[0] = scope.sql;
          // Insert current query into our local subset of history
          this.history.unshift(this.history[0]);
          // Insert current query into a copy of global history
          var history = angular.fromJson(window.localStorage.getItem('sqlHistory'));
          history.unshift(this.history[0]);
          while (history.length > maxLength) {
            history.pop();
          }
          // Update global history
          window.localStorage.setItem('sqlHistory', angular.toJson(history));
        };
        // Move one row up in local history
        this.navigateUp = function() {
          if (this.index < this.history.length - 1) {
            if (this.index === 0) {
              // Update current query in local history
              this.history[0] = scope.sql;
            }
            this.index++;
            scope.sql = this.history[this.index];
          }
        };
        // Move one row down in local history
        this.navigateDown = function() {
          if (this.index > 0) {
            this.index--;
            scope.sql = this.history[this.index];
          }
        };
      }
      scope.sqlHistory = new SqlHistory(scope.maxPersistedHistoryLength);

      scope.project = scope.side && scope.side.state.projectName;
      scope.sort = {
        column: undefined,
        reverse: false,
        select: function(index) {
          index = index.toString();
          if (scope.sort.column === index) {
            if (scope.sort.reverse) {
              // Already reversed by this column. This click turns off sorting.
              scope.sort.column = undefined;
            } else {
              // Already sorting by this column. This click reverses.
              scope.sort.reverse = true;
            }
          } else {
            // Not sorted yet. This click sorts by this column.
            scope.sort.column = index;
            scope.sort.reverse = false;
          }
        },
        style: function(index) {
          index = index.toString();
          if (index === scope.sort.column) {
            return scope.sort.reverse ? 'sort-desc' : 'sort-asc';
          }
        },
      };

      scope.runSQLQuery = function() {
        if (!scope.sql) {
          scope.result = { $error: 'SQL script must be specified.' };
        } else {
          scope.sqlHistory.save();
          scope.inProgress += 1;
          scope.result = util.nocache(
            '/ajax/runSQLQuery',
            {
              dfSpec: {
                isGlobal: scope.isGlobal,
                directory: scope.directory,
                project: scope.project,
                sql: scope.sql,
              },
              maxRows: parseInt(scope.maxRows),
            });
          scope.result.finally(function() {
            scope.inProgress -= 1;
          });
        }
      };

      scope.$watch('exportFormat', function(exportFormat) {
        if (exportFormat === 'table' ||
            exportFormat === 'segmentation' ||
            exportFormat === 'view') {
          scope.exportKiteTable = '';
        } else if (exportFormat === 'csv') {
          scope.exportPath = '<download>';
          scope.exportDelimiter = ',';
          scope.exportQuote = '"';
          scope.exportHeader = true;
        } else if (exportFormat === 'json') {
          scope.exportPath = '<download>';
        } else if (exportFormat === 'parquet') {
          scope.exportPath = '';
        } else if (exportFormat === 'orc') {
          scope.exportPath = '';
        } else if (exportFormat === 'jdbc') {
          scope.exportJdbcUrl = '';
          scope.exportJdbcTable = '';
          scope.exportMode = 'error';
        }
      });

      scope.export = function() {
        if (!scope.sql) {
          util.error('SQL script must be specified.');
          return;
        }
        var req = {
          dfSpec: {
            isGlobal: scope.isGlobal,
            directory: scope.directory,
            project: scope.project,
            sql: scope.sql,
          },
        };
        scope.inProgress += 1;
        var result;
        if (scope.exportFormat === 'table') {
          req.table = scope.exportKiteTable;
          req.privacy = 'public-read';
          result = util.post('/ajax/exportSQLQueryToTable', req);
        } else if (scope.exportFormat === 'segmentation') {
          result = scope.side.applyOp(
              'Create-segmentation-from-SQL',
              {
                name: scope.exportKiteTable,
                sql: scope.sql
              });
        } else if (scope.exportFormat === 'view') {
          req.name = scope.exportKiteTable;
          req.privacy = 'public-read';
          result = util.post('/ajax/createViewDFSpec', req);
        } else if (scope.exportFormat === 'csv') {
          req.path = scope.exportPath;
          req.delimiter = scope.exportDelimiter;
          req.quote = scope.exportQuote;
          req.header = scope.exportHeader;
          result = util.post('/ajax/exportSQLQueryToCSV', req);
        } else if (scope.exportFormat === 'json') {
          req.path = scope.exportPath;
          result = util.post('/ajax/exportSQLQueryToJson', req);
        } else if (scope.exportFormat === 'parquet') {
          req.path = scope.exportPath;
          result = util.post('/ajax/exportSQLQueryToParquet', req);
        } else if (scope.exportFormat === 'orc') {
          req.path = scope.exportPath;
          result = util.post('/ajax/exportSQLQueryToORC', req);
        } else if (scope.exportFormat === 'jdbc') {
          req.jdbcUrl = scope.exportJdbcUrl;
          req.table = scope.exportJdbcTable;
          req.mode = scope.exportMode;
          result = util.post('/ajax/exportSQLQueryToJdbc', req);
        } else {
          throw new Error('Unexpected export format: ' + scope.exportFormat);
        }
        result.finally(function() {
          scope.inProgress -= 1;
        });
        result.then(function(result) {
          scope.showExportOptions = false;
          scope.success = 'Results exported.';
          if (result && result.download) {
            // Fire off the download.
            $window.location =
              '/downloadFile?q=' + encodeURIComponent(JSON.stringify(result.download));
          }
          if (scope.exportFormat === 'table' || scope.exportFormat === 'view') {
            $rootScope.$broadcast('new table or view', scope);
          }
        });
      };

      scope.$watch('showExportOptions', function(showExportOptions) {
        if (showExportOptions) {
          scope.success = ''; // Hide previous success message to avoid confusion.
        }
      });

      scope.reportSQLError = function() {
        util.reportRequestError(scope.result, 'Error executing query.');
      };

      scope.onLoad = function(editor) {
        editor.setOptions({
          autoScrollEditorIntoView : true,
          maxLines : 500
        });

        editor.commands.addCommand({
          name: 'navigateUp',
          bindKey: {
            win: 'Ctrl-Up',
            mac: 'Command-Up',
            sender: 'editor|cli'
          },
          exec: function() { scope.$apply(function() { scope.sqlHistory.navigateUp(); }); }
        });
        editor.commands.addCommand({
          name: 'navigateDown',
          bindKey: {
            win: 'Ctrl-Down',
            mac: 'Command-Down',
            sender: 'editor|cli'
          },
          exec: function() { scope.$apply(function() { scope.sqlHistory.navigateDown(); }); }
        });
      };
    }
  };
});
