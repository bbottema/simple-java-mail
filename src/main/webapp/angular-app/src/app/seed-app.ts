import {Component} from '@angular/core';
import {RouteConfig, ROUTER_DIRECTIVES} from '@angular/router-deprecated';

import {Home} from './components/home/home';
import {About} from './components/about/about';

@Component({
  selector: 'simple-java-mail-app',
  directives: [ROUTER_DIRECTIVES],
  templateUrl: 'app/seed-app.html'
})

@RouteConfig([
  { path: '/home',       component: Home,        name: 'Home', useAsDefault: true },
  { path: '/about',      component: About,       name: 'About' }
])

export class SeedApp { }
