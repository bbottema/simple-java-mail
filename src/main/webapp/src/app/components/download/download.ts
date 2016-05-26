import {Component, Inject, OnInit} from '@angular/core';
import {ROUTER_DIRECTIVES} from '@angular/router';
import {SimplePageScroll} from 'ng2-simple-page-scroll';
import {MavenSearchService} from 'services/maven-search.service';

@Component({
  template: require('app/components/download/download.html'),
  directives: [ROUTER_DIRECTIVES, SimplePageScroll],
  providers: [MavenSearchService]
})

export class Download implements OnInit {
  private groupId = 'org.codemonkey.simplejavamail';
  private artifact = 'simple-java-mail';

  private latestVersion:any;

  constructor(private mavenSearchService:MavenSearchService) {
  }

  ngOnInit():any {
    this.latestVersion = this.mavenSearchService.getLatestVersion(this.groupId, this.artifact);
  }
}
