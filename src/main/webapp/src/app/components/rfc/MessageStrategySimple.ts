import {MessageStrategy} from "./MessageStrategy";
import {Email} from "./Email";

export class MessageStrategySimple extends MessageStrategy {
  
  compatibleWithEmail(email: Email): boolean {
		return !MessageStrategy.emailContainsMixedContent(email) &&
      !MessageStrategy.emailContainsRelatedContent(email) &&
      !MessageStrategy.emailContainsAlternativeContent(email);
	}
	
	public determineMessageStructure(email: Email): string {
    return "<ul>" +
      (email.usePlainText ? "<li class=\"indent\">Plain text (root)</li>" : "") +
      (email.useHTMLText ? "<li class=\"indent\">HTML text (root)</li>" : "") +
      (email.useCalendarEvent ? "<li class=\"indent\">iCalendar text (root)</li>" : "") +
      "     </ul>";
	}
}
