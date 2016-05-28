import {Component} from '@angular/core';
import {SimplePageScroll, SimplePageScrollConfig} from 'ng2-simple-page-scroll';

@Component({
  template: require('app/components/rfc/rfc.html'),
  directives: [SimplePageScroll]
})

export class RfcCompliant {
  constructor() {
    SimplePageScrollConfig.defaultScrollOffset = 0;
  }
}
