import { Injectable, NgZone } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({
  providedIn: 'root',
})
export class SseService {
  private baseUrl = environment.apiUrl;
  private eventSource: EventSource | null = null;

  constructor(private ngZone: NgZone) {}

  /**
   * Kết nối SSE và trả về Observable cho từng event theo tên
   */
  connect(jobId: string): EventSource {
    this.disconnect();
    this.eventSource = new EventSource(
      `${this.baseUrl}/api/download/progress/${jobId}`
    );
    return this.eventSource;
  }

  /**
   * Lắng nghe một event cụ thể, chạy trong Angular Zone
   */
  listenOn<T>(eventSource: EventSource, eventName: string): Observable<T> {
    return new Observable<T>((observer) => {
      const handler = (event: MessageEvent) => {
        this.ngZone.run(() => {
          try {
            const data: T = JSON.parse(event.data);
            observer.next(data);
          } catch (e) {
            observer.error(e);
          }
        });
      };

      eventSource.addEventListener(eventName, handler as EventListener);

      return () => {
        eventSource.removeEventListener(eventName, handler as EventListener);
      };
    });
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
