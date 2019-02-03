import {Email} from "./Email";
import {MessageStrategy} from "./MessageStrategy";

export class MessageStrategyMixedRelated extends MessageStrategy {
  compatibleWithEmail(email: Email): boolean {
		return MessageStrategy.emailContainsMixedContent(email) &&
      MessageStrategy.emailContainsRelatedContent(email) &&
      !MessageStrategy.emailContainsAlternativeContent(email);
	}
  
  public determineMessageStructure(email: Email): string {
    return "<ul>" +
      "   <li class=\"indent\">mixed (root)" +
      "     <ul>" +
      "		    <li class=\"indent\">related" +
      "         <ul>" +
      "			      <li class=\"indent\">HTML text</li>" +
      "			      <li class=\"indent\">embeddable content (ie. images)</li>" +
      "		      </ul>" +
      "       </li>" +
      (email.useEmailForward ? "<li class=\"indent\">forwarded email</li>" : "") +
      (email.useAttachments ? "<li class=\"indent\">downloadable attachments</li>" : "") +
      "	    </ul>" +
      "   </li>" +
      "</ul>";
  }
}
