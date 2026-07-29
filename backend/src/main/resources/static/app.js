/**
 * YouTube Playlist Downloader - AngularJS App
 */
(function () {
  'use strict';

  var app = angular.module('DownloaderApp', []);

  // ============================
  // Service: API
  // ============================
  app.service('ApiService', ['$http', '$q', function ($http, $q) {
    var BASE = '/api';

    this.getPlaylistInfo = function (url) {
      return $http.get(BASE + '/playlist/info', { params: { url: url } });
    };

    this.startDownload = function (payload) {
      return $http.post(BASE + '/download/start', payload);
    };

    this.getJobStatus = function (jobId) {
      return $http.get(BASE + '/download/status/' + jobId);
    };

    this.cancelJob = function (jobId) {
      return $http.post(BASE + '/download/cancel/' + jobId);
    };

    this.getAllJobs = function () {
      return $http.get(BASE + '/download/jobs');
    };
  }]);

  // ============================
  // Service: SSE Manager
  // ============================
  app.service('SseService', ['$rootScope', function ($rootScope) {
    var eventSource = null;

    this.connect = function (jobId, handlers) {
      if (eventSource) {
        eventSource.close();
      }

      eventSource = new EventSource('/api/download/progress/' + jobId);

      // Register event handlers
      angular.forEach(handlers, function (handler, eventName) {
        eventSource.addEventListener(eventName, function (e) {
          var data = JSON.parse(e.data);
          $rootScope.$apply(function () {
            handler(data);
          });
        });
      });

      eventSource.onerror = function (err) {
        console.error('SSE error:', err);
        if (handlers.onError) {
          $rootScope.$apply(function () {
            handlers.onError(err);
          });
        }
      };

      return eventSource;
    };

    this.disconnect = function () {
      if (eventSource) {
        eventSource.close();
        eventSource = null;
      }
    };
  }]);

  // ============================
  // Main Controller
  // ============================
  app.controller('MainCtrl', [
    '$scope', '$timeout', '$interval', 'ApiService', 'SseService',
    function ($scope, $timeout, $interval, ApiService, SseService) {

      // === State ===
      $scope.form = {
        playlistUrl: '',
        folderName: '',
        savePath: ''
      };

      $scope.state = 'idle'; // idle | fetching | downloading | done
      $scope.job = null;
      $scope.playlistPreview = null;
      $scope.errorMsg = null;
      $scope.successMsg = null;
      $scope.resultData = null;
      $scope.logs = [];

      // === Helpers ===
      $scope.getStatusBadge = function (status) {
        var map = {
          'PENDING': 'badge-pending',
          'DOWNLOADING': 'badge-downloading',
          'COMPLETED': 'badge-completed',
          'FAILED': 'badge-failed',
          'SKIPPED': 'badge-pending'
        };
        return map[status] || 'badge-pending';
      };

      $scope.getStatusText = function (status) {
        var map = {
          'PENDING': 'Chờ',
          'DOWNLOADING': 'Đang tải',
          'COMPLETED': 'Xong',
          'FAILED': 'Lỗi',
          'SKIPPED': 'Bỏ qua'
        };
        return map[status] || status;
      };

      $scope.getJobStatusText = function (status) {
        var map = {
          'PENDING': '⏳ Chuẩn bị',
          'FETCHING_INFO': '🔍 Đang lấy thông tin...',
          'DOWNLOADING': '⬇ Đang tải',
          'COMPLETED': '✅ Hoàn thành',
          'FAILED': '❌ Thất bại',
          'CANCELLED': '⛔ Đã hủy'
        };
        return map[status] || status;
      };

      $scope.isDownloading = function () {
        return $scope.state === 'downloading' ||
          ($scope.job && ($scope.job.status === 'DOWNLOADING' || $scope.job.status === 'FETCHING_INFO'));
      };

      $scope.getLogClass = function (log) {
        if (log.includes('✅') || log.includes('Hoàn thành') || log.includes('Xong')) return 'success';
        if (log.includes('❌') || log.includes('Lỗi') || log.includes('ERROR')) return 'error';
        if (log.includes('⬇') || log.includes('🔍') || log.includes('ℹ')) return 'info';
        return '';
      };

      // === Preview Playlist ===
      $scope.previewPlaylist = function () {
        if (!$scope.form.playlistUrl) {
          $scope.errorMsg = 'Vui lòng nhập URL playlist';
          return;
        }
        $scope.errorMsg = null;
        $scope.state = 'fetching';
        $scope.playlistPreview = null;

        ApiService.getPlaylistInfo($scope.form.playlistUrl)
          .then(function (res) {
            $scope.playlistPreview = res.data;
            $scope.addLog('ℹ Tìm thấy ' + res.data.total + ' video trong playlist');
          })
          .catch(function (err) {
            $scope.errorMsg = (err.data && err.data.message) || 'Không thể lấy thông tin playlist';
          })
          .finally(function () {
            $scope.state = 'idle';
          });
      };

      // === Start Download ===
      $scope.startDownload = function () {
        $scope.errorMsg = null;
        $scope.successMsg = null;
        $scope.resultData = null;

        if (!$scope.form.playlistUrl) {
          $scope.errorMsg = 'Vui lòng nhập YouTube Playlist URL';
          return;
        }
        if (!$scope.form.folderName) {
          $scope.errorMsg = 'Vui lòng nhập tên folder';
          return;
        }
        if (!$scope.form.savePath) {
          $scope.errorMsg = 'Vui lòng nhập đường dẫn lưu';
          return;
        }

        $scope.state = 'downloading';
        $scope.logs = [];
        $scope.job = {
          status: 'PENDING',
          totalVideos: 0,
          completedVideos: 0,
          failedVideos: 0,
          progressPercent: 0,
          currentVideoTitle: '',
          currentVideoProgress: 0,
          videos: []
        };

        var payload = {
          playlistUrl: $scope.form.playlistUrl,
          folderName: $scope.form.folderName,
          savePath: $scope.form.savePath
        };

        ApiService.startDownload(payload)
          .then(function (res) {
            var jobId = res.data.jobId;
            $scope.addLog('🚀 Job ID: ' + jobId);
            connectSse(jobId);
          })
          .catch(function (err) {
            $scope.state = 'idle';
            $scope.errorMsg = (err.data && err.data.message) || 'Lỗi khởi động download';
          });
      };

      // === SSE Connection ===
      function connectSse(jobId) {
        SseService.connect(jobId, {
          'status': function (data) {
            // Update job state
            $scope.job.status = data.status;
            $scope.job.totalVideos = data.totalVideos;
            $scope.job.completedVideos = data.completedVideos;
            $scope.job.failedVideos = data.failedVideos;
            $scope.job.progressPercent = data.progressPercent;
            $scope.job.currentVideoTitle = data.currentVideoTitle;
            $scope.job.currentVideoProgress = data.currentVideoProgress;

            // Update logs from backend
            if (data.logs && data.logs.length > 0) {
              $scope.logs = data.logs;
              scrollLogToBottom();
            }
          },

          'playlist_info': function (data) {
            $scope.job.totalVideos = data.total;
            $scope.job.videos = data.videos;
          },

          'video_start': function (data) {
            updateVideo(data.video);
            $scope.job.currentVideoTitle = data.video.title;
            scrollLogToBottom();
          },

          'progress': function (data) {
            // Update individual video progress
            updateVideoProgress(data.videoId, data.percent);
            $scope.job.currentVideoProgress = data.percent;
          },

          'video_done': function (data) {
            updateVideo(data.video);
          },

          'done': function (data) {
            $scope.state = 'done';
            $scope.resultData = data;
            SseService.disconnect();
            scrollLogToBottom();
          },

          'onError': function () {
            // SSE connection lost - try to fetch status via polling
            if ($scope.state === 'downloading') {
              $scope.addLog('⚠ Kết nối SSE bị gián đoạn');
            }
          }
        });
      }

      function updateVideo(video) {
        if (!$scope.job.videos) $scope.job.videos = [];
        var found = false;
        for (var i = 0; i < $scope.job.videos.length; i++) {
          if ($scope.job.videos[i].id === video.id) {
            angular.extend($scope.job.videos[i], video);
            found = true;
            break;
          }
        }
        if (!found) {
          $scope.job.videos.push(video);
        }
      }

      function updateVideoProgress(videoId, percent) {
        if (!$scope.job.videos) return;
        for (var i = 0; i < $scope.job.videos.length; i++) {
          if ($scope.job.videos[i].id === videoId) {
            $scope.job.videos[i].progress = percent;
            break;
          }
        }
      }

      $scope.addLog = function (msg) {
        var now = new Date();
        var ts = [now.getHours(), now.getMinutes(), now.getSeconds()]
          .map(function (n) { return n < 10 ? '0' + n : n; })
          .join(':');
        $scope.logs.push('[' + ts + '] ' + msg);
        if ($scope.logs.length > 300) {
          $scope.logs.shift();
        }
        scrollLogToBottom();
      };

      function scrollLogToBottom() {
        $timeout(function () {
          var terminal = document.getElementById('logTerminal');
          if (terminal) {
            terminal.scrollTop = terminal.scrollHeight;
          }
        }, 50);
      }

      // === Cancel Job ===
      $scope.cancelJob = function () {
        if ($scope.job && $scope.job.id) {
          ApiService.cancelJob($scope.job.id)
            .then(function () {
              $scope.addLog('⛔ Đã gửi lệnh hủy');
            });
        }
      };

      // === Reset ===
      $scope.reset = function () {
        SseService.disconnect();
        $scope.state = 'idle';
        $scope.job = null;
        $scope.playlistPreview = null;
        $scope.errorMsg = null;
        $scope.successMsg = null;
        $scope.resultData = null;
        $scope.logs = [];
      };
    }
  ]);

})();
