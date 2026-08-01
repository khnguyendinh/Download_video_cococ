import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

type CallbackState = 'loading' | 'success' | 'error';

@Component({
  selector: 'app-oauth-callback',
  templateUrl: './oauth-callback.component.html',
  styleUrls: ['./oauth-callback.component.scss'],
})
export class OAuthCallbackComponent implements OnInit, OnDestroy {
  state: CallbackState = 'loading';
  errorMsg = '';
  countdown = 3;

  private countdownTimer?: ReturnType<typeof setInterval>;

  constructor(private route: ActivatedRoute, private router: Router) {}

  ngOnInit(): void {
    const success = this.route.snapshot.queryParamMap.get('success');
    const error   = this.route.snapshot.queryParamMap.get('error');

    if (success === 'true') {
      this.state = 'success';

      // Thông báo cho tab cha (upload page) qua postMessage
      try {
        window.opener?.postMessage({ type: 'YOUTUBE_AUTH_SUCCESS' }, window.location.origin);
      } catch (e) { /* ignore */ }

      // Bắt đầu đếm ngược rồi tự đóng tab
      this.countdownTimer = setInterval(() => {
        this.countdown--;
        if (this.countdown <= 0) {
          clearInterval(this.countdownTimer);
          // Thử đóng tab — nếu không đóng được thì redirect về /upload
          try { window.close(); } catch (e) { /* ignore */ }
          setTimeout(() => this.router.navigate(['/upload']), 500);
        }
      }, 1000);

    } else {
      this.state = 'error';
      this.errorMsg = error ? decodeURIComponent(error) : 'Xác thực thất bại.';
    }
  }

  ngOnDestroy(): void {
    if (this.countdownTimer) clearInterval(this.countdownTimer);
  }

  goToUpload(): void {
    this.router.navigate(['/upload']);
  }
}
