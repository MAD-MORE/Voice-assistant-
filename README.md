# Voice Assistant

A voice-first Android assistant for elderly users who have difficulty seeing or operating a smartphone.

## Goals
- Hands-free calling with English and Akan/Twi commands.
- Personalized names and relationship aliases such as “my son”.
- Fuzzy contact matching for Ghanaian names and pronunciation variation.
- Spoken confirmation whenever confidence is not high enough.
- Voice onboarding so the user does not need to navigate the screen.
- Incoming-call spoken announcements with simple answer/reject actions.
- Local-first contact aliases and preferences.
- Accessibility-first, large-text fallback UI; voice remains the primary interface.

## Example commands
- “Call Gyamera.”
- “Frɛ Gyamera.”
- “Frɛ me ba.” (Call my child.)
- “Call my son.”
- “Stop listening.”
- “Who can I call?”
- “Remember Gyamera as my son.”

## Build
Open the repository in Android Studio and run the `app` module. The project targets Android API 35 and uses Kotlin.

## Safety
The assistant does not silently place ambiguous calls. It asks the user to confirm when multiple contacts or low-confidence speech recognition could produce the wrong person. Emergency calling is left to the device's native phone/emergency UI.
