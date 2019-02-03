import {Component, OnInit, Input} from '@angular/core';
import {MavenSearchService} from '../../services/maven-search.service';

var sprintf:any = require("sprintf-js").sprintf;

@Component({
  selector: 'maven-dependency-display',
  template: require('./maven-dependency-display.component.html'),
  providers: [MavenSearchService]
})

export class MavenDependencyDisplay implements OnInit {

  @Input() private groupId:string;
  @Input() private artifact:string;

  private static DISPLAY_TEMPLATE:string =
    '<pre><code class="language-markup">&lt;dependency&gt;\n' +
    '\t&lt;groupId&gt;%s&lt;/groupId&gt;\n' +
    '\t&lt;artifactId&gt;%s&lt;/artifactId&gt;\n' +
    '\t&lt;version&gt;%s&lt;/version&gt;\n' +
    '&lt;/dependency&gt;</code></pre>';

  private output:string;

  constructor(private mavenSearchService:MavenSearchService) {
  }

  ngOnInit() {
    this.output = sprintf(MavenDependencyDisplay.DISPLAY_TEMPLATE, this.groupId, this.artifact, '...');
    this.mavenSearchService.fetchLatestVersion(this.groupId, this.artifact)
      .subscribe((latestVersion:string) =>
        this.output = sprintf(MavenDependencyDisplay.DISPLAY_TEMPLATE, this.groupId, this.artifact, latestVersion)
      )
  }
}
