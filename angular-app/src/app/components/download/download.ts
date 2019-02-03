import {Component} from '@angular/core';
import {MavenDependencyDisplay} from '../maven-dependency-display/maven-dependency-display.component';

@Component({
  template: require('./download.html'),
  entryComponents: [MavenDependencyDisplay]
})

export class Download { }
