// The invitation an emailed link names. The link is
// `/#/invitations/<id>?token=<token>`; both ride in the fragment so
// neither reaches a server log. The pair is kept in session storage on
// arrival, so it survives the round trip through sign-in, and cleared
// once the person has accepted or declined.

const KEY = "queenswood.invitation-link";

const LINK = /^#\/invitations\/([^/?]+)(?:\?(.*))?$/;

function stored() {
  try {
    return JSON.parse(sessionStorage.getItem(KEY) ?? "null");
  } catch {
    return null;
  }
}

export function capture_invitation_link() {
  const match = LINK.exec(window.location.hash);
  if (match) {
    const token = new URLSearchParams(match[2] ?? "").get("token");
    const link = { invitationId: decodeURIComponent(match[1]), token };
    try {
      sessionStorage.setItem(KEY, JSON.stringify(link));
    } catch {
      // Without storage the link still works when already signed in.
    }
    return link;
  }
  return stored();
}

export function clear_invitation_link() {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // Nothing was stored.
  }
}
