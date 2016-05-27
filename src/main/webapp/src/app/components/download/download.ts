import {Component} from '@angular/core';
import {ROUTER_DIRECTIVES} from '@angular/router';
import {SimplePageScroll} from 'ng2-simple-page-scroll';
import {MavenDependencyDisplay} from 'components/maven-dependency-display/maven-dependency-display.component';

@Component({
  template: require('app/components/download/download.html'),
  directives: [ROUTER_DIRECTIVES, SimplePageScroll, MavenDependencyDisplay]
})

export class Download { }
