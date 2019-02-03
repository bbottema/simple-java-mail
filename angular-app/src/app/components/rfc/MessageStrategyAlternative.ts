import {MessageStrategy} from "./MessageStrategy";
import {Email} from "./Email";

export class MessageStrategyAlternative extends MessageStrategy {
  compatibleWithEmail(email: Email): boolean {
    return !MessageStrategy.emailContainsMixedContent(email) &&
      !MessageStrategy.emailContainsRelatedContent(email) &&
      MessageStrategy.emailContainsAlternativeContent(email);
  }
  
  public determineMessageStructure(email: Email): string {
    return "<ul>" +
      "  <li class=\"indent\">alternative (root)" +
      "     <ul>" +
      (email.usePlainText ? "<li class=\"indent\">Plain text</li>" : "") +
      (email.useHTMLText ? "<li class=\"indent\">HTML text</li>" : "") +
      (email.useCalendarEvent ? "<li class=\"indent\">iCalendar text</li>" : "") +
      "     </ul>" +
      "   </li>" +
      "</ul>";
  }
}
