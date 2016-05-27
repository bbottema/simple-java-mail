import {Injectable} from '@angular/core';
import {Http, Response} from '@angular/http';
import 'rxjs/add/operator/map';
import {Observable} from "rxjs/Observable";
import {Subject} from "rxjs/Subject";

var sprintf:any = require("sprintf-js").sprintf;

@Injectable()
export class MavenSearchService {
  private static endpoint:string = 'https://img.shields.io/maven-central/v/%s/%s.json';

  private latestVersion = new Subject();

  constructor(private http:Http) {
  }

  public fetchLatestVersion(groupId:string, artifact:string):Observable<string> {
    this.http
      .get(sprintf(MavenSearchService.endpoint, groupId, artifact))
      .map((res:Response) => res.json().value.replace('v', ''))
      .subscribe((latestVersion:string) => this.latestVersion.next(latestVersion));
    return this.latestVersion;
  }
}
