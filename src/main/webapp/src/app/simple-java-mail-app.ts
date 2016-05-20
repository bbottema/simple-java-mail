declare var Prism:any;

import {Component, ViewEncapsulation, AfterViewChecked} from '@angular/core';
import {Router, Routes, ROUTER_DIRECTIVES} from '@angular/router';

import {About} from './components/about/about';
import {Features} from './components/features/features';
import {Debugging} from './components/debugging/debugging';
import {Download} from './components/download/download';
import {Contact} from './components/contact/contact';

@Component({
  selector: 'simple-java-mail-app',
  directives: [ROUTER_DIRECTIVES],
  template: require('app/simple-java-mail-app.html'),
  styles: [require('app/simple-java-mail-app.less')],
  encapsulation: ViewEncapsulation.None
})

@Routes([
  {path: '/about', component: About},
  {path: '/features', component: Features},
  {path: '/debugging', component: Debugging},
  {path: '/download', component: Download},
  {path: '/contact', component: Contact},
  {path: '', component: About}
])

export class SimpleJavaMailApp implements AfterViewChecked {
  constructor(private router:Router) {
  }

  ngAfterViewChecked():any {
    return Prism.highlightAll();
  }
}
