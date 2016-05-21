import {Component} from '@angular/core';
import {SimplePageScroll, SimplePageScrollConfig} from 'ng2-simple-page-scroll';

@Component({
  template: require('app/components/debugging/debugging.html'),
  directives: [SimplePageScroll]
})

export class Debugging { }
