import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';

// Ng-Zorro modules
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzProgressModule } from 'ng-zorro-antd/progress';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzResultModule } from 'ng-zorro-antd/result';
import { NzStatisticModule } from 'ng-zorro-antd/statistic';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageModule } from 'ng-zorro-antd/message';
import { NzToolTipModule } from 'ng-zorro-antd/tooltip';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';

import { DownloaderComponent } from './downloader/downloader.component';
import { UploadComponent } from './upload/upload.component';
import { OAuthCallbackComponent } from './oauth-callback/oauth-callback.component';
import { ConvertComponent } from './convert/convert.component';
import { PagesRoutingModule } from './pages-routing.module';

@NgModule({
  declarations: [DownloaderComponent, UploadComponent, OAuthCallbackComponent, ConvertComponent],
  imports: [
    CommonModule,
    ReactiveFormsModule,
    FormsModule,
    PagesRoutingModule,
    // Ng-Zorro
    NzCardModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzProgressModule,
    NzTagModule,
    NzSpinModule,
    NzAlertModule,
    NzResultModule,
    NzStatisticModule,
    NzIconModule,
    NzMessageModule,
    NzToolTipModule,
    NzModalModule,
    NzPopconfirmModule,
    NzCheckboxModule,
  ],
})
export class PagesModule {}
