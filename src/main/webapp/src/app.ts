import {LocationStrategy, HashLocationStrategy} from '@angular/common';
import {bootstrap} from '@angular/platform-browser-dynamic';
import {provide, enableProdMode} from '@angular/core';
import {ROUTER_PROVIDERS} from '@angular/router';

import {SimpleJavaMailApp} from './app/simple-java-mail-app';

require('./index.html');

// enableProdMode()

bootstrap(SimpleJavaMailApp, [
  ROUTER_PROVIDERS,
  provide(LocationStrategy, {useClass: HashLocationStrategy})
])
.catch(err => console.error(err));
