/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 ForgeRock Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */

package org.identityconnectors.framework.common.exceptions;

/**
 * AccountLockedException signals that an account was locked.
 * <p/>
 * This exception may be thrown by a <code>Connector</code> if it determines
 * that authentication is being attempted on a locked account.
 *
 * @author Laszlo Hordos
 * @since 1.4
 * @see javax.security.auth.login.AccountLockedException
 */
public class AccountLockedException extends InvalidAccountException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new AccountLockedException exception with <code>null</code>
     * as its detail message. The cause is not initialized, and may subsequently
     * be initialized by a call to {@link #initCause}.
     */
    public AccountLockedException() {
        super();
    }

    /**
     * Constructs a new AccountLockedException exception with the specified
     * detail message. The cause is not initialized, and may subsequently be
     * initialized by a call to {@link #initCause}.
     *
     * @param message
     *            the detail message. The detail message is is a String that
     *            describes this particular exception and saved for later
     *            retrieval by the {@link #getMessage()} method.
     */
    public AccountLockedException(String message) {
        super(message);
    }

    /**
     * Constructs a new AccountLockedException exception with the specified
     * cause and a detail message of
     * <tt>(cause==null ? null : cause.toString())</tt> (which typically
     * contains the class and detail message of <tt>cause</tt>). This
     * constructor is useful for InvalidAccountException exceptions that are
     * little more than wrappers for other throwables.
     *
     * @param cause
     *            the cause (which is saved for later retrieval by the
     *            {@link #getCause()} method). (A <tt>null</tt> value is
     *            permitted, and indicates that the cause is nonexistent or
     *            unknown.)
     */
    public AccountLockedException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new AccountLockedException exception with the specified
     * detail message and cause.
     * <p>
     * Note that the detail message associated with <code>cause</code> is
     * <i>not</i> automatically incorporated in this Connector exception's
     * detail message.
     *
     * @param message
     *            the detail message (which is saved for later retrieval by the
     *            {@link #getMessage()} method).
     * @param cause
     *            the cause (which is saved for later retrieval by the
     *            {@link #getCause()} method). (A <tt>null</tt> value is
     *            permitted, and indicates that the cause is nonexistent or
     *            unknown.)
     */
    public AccountLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
