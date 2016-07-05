import {Component} from '@angular/core';
import {SimplePageScroll, SimplePageScrollConfig} from 'ng2-simple-page-scroll';
import {ROUTER_DIRECTIVES} from '@angular/router'; // needed for SimplePageScroll to nav to other views

@Component({
  template: require('app/components/features/features.html'),
  directives: [ROUTER_DIRECTIVES, SimplePageScroll]
})

export class Features {
  constructor() {
    SimplePageScrollConfig.defaultScrollOffset = 0;
  }
}
