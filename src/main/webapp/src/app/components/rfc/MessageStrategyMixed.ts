import {Email} from "./Email";
import {MessageStrategy} from "./MessageStrategy";

export class MessageStrategyMixed extends MessageStrategy {
  compatibleWithEmail(email: Email): boolean {
		return MessageStrategy.emailContainsMixedContent(email) &&
      !MessageStrategy.emailContainsRelatedContent(email) &&
      !MessageStrategy.emailContainsAlternativeContent(email);
	}
  
  public determineMessageStructure(email: Email): string {
    return "<ul>" +
      "  <li class=\"indent\">mixed (root)" +
      "     <ul>" +
      (email.usePlainText ? "<li class=\"indent\">Plain text</li>" : "") +
      (email.useHTMLText ? "<li class=\"indent\">HTML text</li>" : "") +
      (email.useCalendarEvent ? "<li class=\"indent\">iCalendar text</li>" : "") +
      (email.useEmailForward ? "<li class=\"indent\">forwarded email</li>" : "") +
      (email.useAttachments ? "<li class=\"indent\">downloadable attachments</li>" : "") +
      "     </ul>" +
      "   </li>" +
      "</ul>";
  }
}
