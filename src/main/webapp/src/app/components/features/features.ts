import {Component} from '@angular/core';
import {PageScroll} from 'ng2-page-scroll';

@Component({
  template: require('app/components/features/features.html'),
  directives: [PageScroll]
})

export class Features { }
