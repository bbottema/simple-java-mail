import { NgModule, enableProdMode } from '@angular/core'
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { rootRouterConfig } from './app/simple-java-mail-routes';
import { SimpleJavaMailApp } from './app/simple-java-mail-app';
import { BrowserModule } from '@angular/platform-browser';
import { HttpModule } from '@angular/http';

import {Ng2SimplePageScrollModule} from 'ng2-simple-page-scroll/ng2-simple-page-scroll';

import {About} from './app/components/about/about';
import {Features} from './app/components/features/features';
import {Cli} from './app/components/cli/cli';
import {Configuration} from './app/components/configuration/configuration';
import {Debugging} from './app/components/debugging/debugging';
import {RfcCompliant} from './app/components/rfc/rfc';
import {Download} from './app/components/download/download';
import {Contact} from './app/components/contact/contact';
import {Dependencies} from "./app/components/dependencies/dependencies";
import {MavenDependencyDisplay} from './app/components/maven-dependency-display/maven-dependency-display.component';

// enableProdMode();

require('./index.html');

@NgModule({
  declarations: [
    SimpleJavaMailApp,
    About,
    Features,
    Cli,
    Configuration,
    Debugging,
    RfcCompliant,
    Download,
    Contact,
    Dependencies,
    MavenDependencyDisplay
  ],
  imports: [
    BrowserModule,
    HttpModule,
    FormsModule,
    RouterModule.forRoot(rootRouterConfig, { useHash: true }),
    Ng2SimplePageScrollModule.forRoot()
  ],
  bootstrap: [ SimpleJavaMailApp ]
})

export class AppModule {

}
