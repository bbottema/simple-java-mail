import {Component, ViewEncapsulation} from '@angular/core';
import {Router, RouteConfig, ROUTER_DIRECTIVES} from '@angular/router-deprecated';

import {About} from './components/about/about';
import {Features} from './components/features/features';
import {Download} from './components/download/download';
import {Contact} from './components/contact/contact';

@Component({
  selector: 'simple-java-mail-app',
  directives: [ROUTER_DIRECTIVES],
  template: require('app/simple-java-mail-app.html'),
  styles: [require('app/simple-java-mail-app.less')],
  encapsulation: ViewEncapsulation.None
})

@RouteConfig([
  {path: '/about', component: About, name: 'About', useAsDefault: true},
  {path: '/features', component: Features, name: 'Features'},
  {path: '/download', component: Download, name: 'Download'},
  {path: '/contact', component: Contact, name: 'Contact'}
])

export class SimpleJavaMailApp {
  constructor(private router: Router) {}
}
