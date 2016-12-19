declare var Prism:any;

import {Component, ViewEncapsulation, AfterViewChecked, ElementRef} from '@angular/core';
import {Router} from '@angular/router';
import {PageScrollConfig} from 'ng2-page-scroll';

require('./lib/prism.ts');

@Component({
  selector: 'simple-java-mail-app',
  template: require('./simple-java-mail-app.html'),
  styles: [require('./simple-java-mail-app.less')],
  encapsulation: ViewEncapsulation.None
})

export class SimpleJavaMailApp implements AfterViewChecked {
  // router is used by the template (#navigation)
  constructor(private router:Router, private el:ElementRef) {
    PageScrollConfig.defaultScrollOffset = 0;
    PageScrollConfig.defaultDuration = 0;
  }

  // scrollToTop is used by the template
  scrollToTop():void {
    this.el.nativeElement.ownerDocument.body.scrollTop = 0;
  }

  ngAfterViewChecked():any {
    return Prism.highlightAll();
  }
}
