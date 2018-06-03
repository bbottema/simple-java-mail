export class Email {
  constructor(readonly usePlainText: boolean,
              readonly useHTMLText: boolean,
              readonly useEmbeddedContent: boolean,
              readonly useCalendarEvent: boolean,
              readonly useAttachments: boolean,
              readonly useEmailForward: boolean,
  ) {
  }
}
