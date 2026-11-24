import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DownloaderComponent } from './downloader/downloader.component';
import { UploadComponent } from './upload/upload.component';
import { OAuthCallbackComponent } from './oauth-callback/oauth-callback.component';
import { ConvertComponent } from './convert/convert.component';
import { SiteDownloadComponent } from './site-download/site-download.component';

const routes: Routes = [
  { path: '',             component: DownloaderComponent },
  { path: 'course',      component: SiteDownloadComponent },
  { path: 'upload',      component: UploadComponent },
  { path: 'convert',     component: ConvertComponent },
  { path: 'oauth-callback', component: OAuthCallbackComponent },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class PagesRoutingModule {}

