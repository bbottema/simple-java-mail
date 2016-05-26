import {Injectable} from '@angular/core';
import {Http, Response} from '@angular/http';
import 'rxjs/add/operator/map';
import 'rxjs/add/operator/toPromise';

@Injectable()
export class MavenSearchService {
  // private endpoint:string = 'http://search.maven.org/solrsearch/select?q=g:%22org.codemonkey.simplejavamail%22+AND+a:%simple-java-mail%22&core=gav&rows=1&wt=json';
  private endpoint:string = 'https://repository.sonatype.org/service/local/artifact/maven/resolve?r=central-proxy&g=org.codemonkey.simplejavamail&a=simple-java-mail&v=LATEST';

  constructor(private http:Http) {
  }

  getLatestVersion(groupId:string, artifact:string) {
    return this.http
      .get(this.endpoint)
      .map((res:Response) => res.json().response.docs[0].v)
      .toPromise();
  }
}
