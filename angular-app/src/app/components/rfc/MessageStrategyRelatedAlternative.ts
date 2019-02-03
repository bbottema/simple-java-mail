import {Email} from "./Email";
import {MessageStrategy} from "./MessageStrategy";

export class MessageStrategyRelatedAlternative extends MessageStrategy {
  compatibleWithEmail(email: Email): boolean {
    return !MessageStrategy.emailContainsMixedContent(email) &&
      MessageStrategy.emailContainsRelatedContent(email) &&
      MessageStrategy.emailContainsAlternativeContent(email);
  }
  
  public determineMessageStructure(email: Email): string {
    return "<ul>" +
      "   <li class=\"indent\">related (root)<ul>" +
      "   	<li class=\"indent\">alternative" +
      "       <ul>" +
      (email.usePlainText ? "<li class=\"indent\">Plain text</li>" : "") +
      (email.useHTMLText ? "<li class=\"indent\">HTML text</li>" : "") +
      (email.useCalendarEvent ? "<li class=\"indent\">iCalendar text</li>" : "") +
      "		    </ul>" +
      "    </li>" +
      "		<li class=\"indent\">embeddable content (ie. images)</li>" +
      "	</ul>" +
      "</li>";
  }
}
