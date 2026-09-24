// Every status an operation's description names is one the operation
// documents with an example of that refusal. The examples every `/v1`
// route inherits from the group's default responses name no particular
// refusal, so they do not count.
const inherited = new Set([
  'BadRequest',
  'Unauthorized',
  'Forbidden',
  'InternalServerError',
  'BadResponse',
  'Contention',
  'Timeout',
]);

module.exports = (operation, _options, context) => {
  const named = new Set((operation.description || '').match(/\b[45]\d\d\b/g) || []);
  const results = [];
  for (const status of named) {
    const response = (operation.responses || {})[status] || {};
    const examples = Object.values(response.content || {})
      .flatMap((media) => Object.values(media.examples || {}))
      .map((example) => (example.$ref || '').split('/').pop())
      .filter((name) => name && !inherited.has(name));
    if (examples.length === 0) {
      results.push({
        message: `The description names ${status}, but no ${status} response documents that refusal.`,
        path: [...context.path, 'description'],
      });
    }
  }
  return results;
};
