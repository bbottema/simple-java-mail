import {Component, AfterViewChecked} from '@angular/core';
import {Router, RouteConfig, ROUTER_DIRECTIVES} from '@angular/router-deprecated';

import {About} from './components/about/about';
import {Features} from './components/features/features';
import {Examples} from './components/examples/examples';
import {Download} from './components/download/download';
import {Contact} from './components/contact/contact';

let routeConfig = [
  {path: '/about', component: About, name: 'About', useAsDefault: true},
  {path: '/features', component: Features, name: 'Features'},
  {path: '/examples', component: Examples, name: 'Examples'},
  {path: '/download', component: Download, name: 'Download'},
  {path: '/contact', component: Contact, name: 'Contact'}
];

@Component({
  selector: 'simple-java-mail-app',
  directives: [ROUTER_DIRECTIVES],
  templateUrl: 'app/simple-java-mail-app.html',
  styleUrls: ['app/simple-java-mail-app.css']
})

@RouteConfig(routeConfig)

export class SimpleJavaMailApp implements AfterViewChecked {
  public isInitialized: boolean = false;
  private routes: Array<any> = [];
  private p: Promise<any>;

  constructor(private router: Router) {}

  ngAfterViewChecked() {
    if (!this.isInitialized) {
      if (this.routes.length != routeConfig.length) {
        var route = routeConfig[this.routes.length];
        this.routes.push(route);
        this.p = (this.p) ?
          this.p.then(_ => this.router.navigate([route.name])) :
          this.router.navigate([route.name]);
        this.router.navigate([route.name]);
      } else {
        this.isInitialized = true;
        this.router.navigate([routeConfig[0].name]);
      }
    }
  }
}
