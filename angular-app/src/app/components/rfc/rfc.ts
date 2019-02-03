import {Component, Input} from '@angular/core';
import {MessageStrategy} from "./MessageStrategy";
import {MessageStrategyAlternative} from "./MessageStrategyAlternative";
import {MessageStrategyMixed} from "./MessageStrategyMixed";
import {MessageStrategyRelated} from "./MessageStrategyRelated";
import {MessageStrategyMixedRelatedAlternative} from "./MessageStrategyMixedRelatedAlternative";
import {MessageStrategyRelatedAlternative} from "./MessageStrategyRelatedAlternative";
import {MessageStrategyMixedAlternative} from "./MessageStrategyMixedAlternative";
import {MessageStrategyMixedRelated} from "./MessageStrategyMixedRelated";
import {MessageStrategySimple} from "./MessageStrategySimple";
import {Email} from "./Email";

@Component({
  template: require('./rfc.html')
})
export class RfcCompliant {
  @Input() usePlainText: boolean;
  @Input() useHTMLText: boolean;
  @Input() useEmbeddedContent: boolean;
  @Input() useCalendarEvent: boolean;
  @Input() useAttachments: boolean;
  @Input() useEmailForward: boolean;
  
  messageStructure: string;
  
  private static readonly STRATEGIES: Array<MessageStrategy> = [
    new MessageStrategySimple(),
    new MessageStrategyAlternative(),
    new MessageStrategyRelated(),
    new MessageStrategyMixed(),
    new MessageStrategyMixedRelated(),
    new MessageStrategyMixedAlternative(),
    new MessageStrategyRelatedAlternative(),
    new MessageStrategyMixedRelatedAlternative()
  ];
  
  updateMessageStructure(): void {
    if (!this.useHTMLText) {
      this.useEmbeddedContent = false;
    }
    
    this.messageStructure = RfcCompliant.determineFormalStructure(new Email(
      this.usePlainText,
      this.useHTMLText,
      this.useEmbeddedContent,
      this.useCalendarEvent,
      this.useAttachments,
      this.useEmailForward));
  }
  
  private static determineFormalStructure(email: Email): string {
    for (const s of RfcCompliant.STRATEGIES) {
      if (s.compatibleWithEmail(email)) {
        return s.determineMessageStructure(email);
      }
    }
    throw new Error("email config not recognized properly");
  }
}
