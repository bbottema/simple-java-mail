import {Component} from '@angular/core';
import {SimplePageScroll, SimplePageScrollConfig} from 'ng2-simple-page-scroll';

@Component({
  template: require('app/components/configuration/configuration.html'),
  directives: [SimplePageScroll]
})

export class Configuration { }
