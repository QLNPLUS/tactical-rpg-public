package com.tactical.neoforge.battle;

/**
 * The plain mixin state of one controlled breeze, and nothing else.
 *
 * <p>No synched data is registered on a vanilla class and no field is invented: the handler is ordinary mixin
 * state, so a wild breeze keeps every vanilla behaviour and a released one is a plain breeze again.</p>
 */
public interface TacticalBreezeAccess {

    void tactical$setHandler(BreezeHandler handler);

    @javax.annotation.Nullable
    BreezeHandler tactical$handler();
}
