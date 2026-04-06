// this is an example of a twilio function created on the twilio console

// twilio console: This is your new function. To start, set the name and path on the left.

exports.handler = function(context, event, callback) {

  // Check for a custom password sent from your Android app
  if (event.secret_key !== "MY_SECRET_abc") {
    const response = new Twilio.Response();
    response.setStatusCode(401);
    response.setBody("Invalid Etiquette or Bouquet");
    return callback(null, response);
  }

  const client = context.getTwilioClient();

  // 'to' and 'from' will be sent from your Android App
  client.calls.create({
     twiml: '<Response><Hangup/></Response>', // Hang up immediately if they answer
     to: event.to,
     from: event.from,
     timeout: 5 // The "One Ring" secret: 5 seconds is roughly 1 ring
  })
  .then(call => callback(null, 'Call triggered: ' + call.sid))
  .catch(err => callback(err));

};