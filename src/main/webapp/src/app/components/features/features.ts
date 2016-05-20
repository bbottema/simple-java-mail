import {Component} from '@angular/core';
import {SimplePageScroll, SimplePageScrollConfig} from 'ng2-simple-page-scroll';

@Component({
  template: require('app/components/features/features.html'),
  directives: [SimplePageScroll]
})

export class Features {
  constructor() {
    SimplePageScrollConfig.defaultScrollOffset = 0;
  }
}
