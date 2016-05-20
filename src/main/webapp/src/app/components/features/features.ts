import {Component} from '@angular/core';
import {PageScroll, PageScrollConfig} from 'ng2-page-scroll';

@Component({
  template: require('app/components/features/features.html'),
  directives: [PageScroll]
})

export class Features {
  constructor() {
    // PageScrollConfig.defaultScrollOffset = 200;
    // PageScrollConfig.defaultDuration = 50;
    // PageScrollConfig._interval = 1000;
  }
}
