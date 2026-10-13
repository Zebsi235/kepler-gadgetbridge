/*  Copyright (C) 2026 Zebsi235

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

/**
 * Notices when the watch has lost its keys for this phone while the phone still
 * holds a bond: a bond wipe, the safe-pair escape or a reflash on the watch.
 *
 * Every Kepler characteristic needs an encrypted link, so the phone's stale
 * keys fail exactly the security step: links end with an authentication or
 * encryption status and never get as far as initialising. Two such links in
 * a row, with no successful initialisation in between, are enough to offer
 * "pair again". A weak radio link fails differently (supervision timeout), and
 * one successful initialisation clears the count.
 */
public final class F91KeplerBondWatch {
    /** Failed links in a row before the user is asked to pair again. */
    static final int SUSPECT_AFTER = 2;

    private int failedLinks;
    private boolean authFailureThisLink;
    private boolean initializedThisLink;
    private boolean offered;

    /**
     * Disconnect reasons (HCI codes, as onConnectionStateChange reports them)
     * that mean "the keys did not work". The same numbers mean other things as
     * GATT statuses, so reads and writes use {@link #isSecurityGattStatus}.
     */
    static boolean isSecurityDisconnect(final int status) {
        switch (status) {
            case 0x05: // authentication failure
            case 0x06: // PIN or key missing: the peer has no key for us
            case 0x3D: // connection terminated due to MIC failure
            case 0x89: // GATT_AUTH_FAIL, as some stacks report it on disconnect
                return true;
            default:
                return false;
        }
    }

    /** GATT read/write statuses that mean "the link is not secure enough". */
    static boolean isSecurityGattStatus(final int status) {
        switch (status) {
            case 0x05: // GATT_INSUFFICIENT_AUTHENTICATION
            case 0x0F: // GATT_INSUFFICIENT_ENCRYPTION
            case 0x89: // GATT_AUTH_FAIL
                return true;
            default:
                // not 0x06: as a GATT status that is REQUEST_NOT_SUPPORTED
                return false;
        }
    }

    public synchronized void onLinkUp() {
        authFailureThisLink = false;
        initializedThisLink = false;
    }

    /**
     * A read or write on this link failed; status as Android reported it.
     * Returns true when it failed the security step before initialising.
     */
    public synchronized boolean onOperationStatus(final int status) {
        if (isSecurityGattStatus(status)) {
            authFailureThisLink = true;
            return !initializedThisLink;
        }
        return false;
    }

    /** What a link that failed the security step before initialising should do next. */
    public enum StuckLink { NONE, WAIT, DROP }

    /**
     * A security failure aborts the initialisation transaction but need not end
     * the link: Android may keep it up, and Gadgetbridge then shows "Connecting"
     * forever. Asked a few seconds after such a failure: wait while Android is
     * still pairing (dropping the link would cancel the pairing), otherwise
     * drop the link so the usual reconnect runs, with the new keys if pairing
     * just finished, and so {@link #onLinkDown} counts the failed link.
     *
     * @param bonding whether Android is pairing with the watch right now
     */
    public synchronized StuckLink stuckLink(final boolean bonding) {
        if (initializedThisLink || !authFailureThisLink) {
            return StuckLink.NONE;
        }
        return bonding ? StuckLink.WAIT : StuckLink.DROP;
    }

    public synchronized void onInitialized() {
        initializedThisLink = true;
        failedLinks = 0;
        offered = false;
    }

    /**
     * The link (or a connect attempt) ended. Returns true exactly once per
     * episode: when the user should be offered "pair again".
     *
     * @param status the disconnect status from onConnectionStateChange
     * @param bonded whether Android still holds a bond for the watch; without
     *               one, the next connect simply pairs anew and nothing is stale
     */
    public synchronized boolean onLinkDown(final int status, final boolean bonded) {
        final boolean failed = !initializedThisLink && (authFailureThisLink || isSecurityDisconnect(status));
        authFailureThisLink = false;
        initializedThisLink = false;
        if (!bonded || !failed) {
            if (!bonded) {
                failedLinks = 0;
            }
            return false;
        }
        failedLinks++;
        if (failedLinks >= SUSPECT_AFTER && !offered) {
            offered = true;
            return true;
        }
        return false;
    }

    synchronized int failedLinks() {
        return failedLinks;
    }
}
