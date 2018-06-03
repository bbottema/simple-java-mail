import {Email} from "./Email";

export abstract class MessageStrategy {
  
  abstract compatibleWithEmail(email: Email): boolean;
  
  abstract determineMessageStructure(email: Email): string;
  
  protected static emailContainsMixedContent(email: Email): boolean {
    return email.useAttachments || email.useEmailForward;
  }
  
  protected static emailContainsRelatedContent(email: Email): boolean {
    return email.useEmbeddedContent;
  }
  
  protected static emailContainsAlternativeContent(email: Email): boolean {
    return (email.usePlainText ? 1 : 0) +
      (email.useHTMLText ? 1 : 0) +
      (email.useCalendarEvent ? 1 : 0) > 1;
  }
}
