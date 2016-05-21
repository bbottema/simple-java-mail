import {Component} from '@angular/core';
import {ROUTER_DIRECTIVES, Router} from '@angular/router';
import {SimplePageScroll} from 'ng2-simple-page-scroll';

@Component({
  template: require('app/components/about/about.html'),
  directives: [ROUTER_DIRECTIVES, SimplePageScroll]
})

export class About {
  constructor(router:Router) {
    // About is the default route, but Angular's new router doesn't support default routes yet
    router.navigate(['/about']);
  }
}
