import { NgModule, enableProdMode } from '@angular/core'
import { RouterModule } from '@angular/router';
import { rootRouterConfig } from './app/simple-java-mail-routes';
import { SimpleJavaMailApp } from './app/simple-java-mail-app';
import { BrowserModule } from '@angular/platform-browser';
import { HttpModule } from '@angular/http';

import {Ng2PageScrollModule} from 'ng2-page-scroll/ng2-page-scroll';

import {About} from './app/components/about/about';
import {Features} from './app/components/features/features';
import {Configuration} from './app/components/configuration/configuration';
import {Debugging} from './app/components/debugging/debugging';
import {RfcCompliant} from './app/components/rfc/rfc';
import {Download} from './app/components/download/download';
import {Contact} from './app/components/contact/contact';
import {MavenDependencyDisplay} from './app/components/maven-dependency-display/maven-dependency-display.component';

// enableProdMode();

require('./index.html');

@NgModule({
  declarations: [
    SimpleJavaMailApp,
    About,
    Features,
    Configuration,
    Debugging,
    RfcCompliant,
    Download,
    Contact,
    MavenDependencyDisplay
  ],
  imports: [
    BrowserModule,
    HttpModule,
    RouterModule.forRoot(rootRouterConfig, { useHash: true }),
    Ng2PageScrollModule.forRoot()
  ],
  bootstrap: [ SimpleJavaMailApp ]
})

export class AppModule {

}
