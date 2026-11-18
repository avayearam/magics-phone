Magic Phone Bridge v0.6 - fix invalid_origin_or_path

1) In the running START_PHONE_CONTROL.bat console, press Ctrl+C and close the process.
2) Extract server.py from this ZIP into your EXISTING project's bridge folder, replacing ONLY server.py.
   Keep contacts.json and settings.json unchanged. Do not re-import contacts or re-pair the extension.
3) Start START_PHONE_CONTROL.bat again. It should display v0.6.
4) Open the existing Chrome extension (v0.5 is compatible) and press the no-call connection test.
5) If the extension still says ChatGPT script not loaded, refresh the chatgpt.com tab.

This hotfix does not modify any audio routing or place any call.
