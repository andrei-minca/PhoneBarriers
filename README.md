Use cases:
- share your phone number, which is qualified to make calls to certain numbers (eg, lifting barriers)
- track and model your device context (GPS data & speed & acceleration & time of day) when making these calls, so that later can be recognized and triggered automatically

Requirements:
- twilio account with a pay-as-you-go plan + the minimum account deposit
- verified phone number through twilio (the one that is qualified to make the calls (FROM))
- twilio function (example provided here) that will act as a serverless endpoint to be called by the app (API calls seem to be free for now!)
