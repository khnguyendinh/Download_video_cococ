import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DownloaderComponent } from './downloader/downloader.component';
import { UploadComponent } from './upload/upload.component';
import { OAuthCallbackComponent } from './oauth-callback/oauth-callback.component';

const routes: Routes = [
  { path: '',             component: DownloaderComponent },
  { path: 'upload',      component: UploadComponent },
  { path: 'oauth-callback', component: OAuthCallbackComponent },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class PagesRoutingModule {}

