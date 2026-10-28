package com.tactical.neoforge.mixin;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.layout.Position;
import com.tactical.neoforge.client.TacticalUiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped compatibility for AUI's native-event cancellation, never other mods' pages. */
@Mixin(value = Document.class, remap = false)
public abstract class TacticalDocumentInputMixin {
    @Inject(method = "interceptsMouseEventsAt", at = @At("HEAD"), cancellable = true)
    private void tactical$nativeInput(Position position, CallbackInfoReturnable<Boolean> result) {
        Boolean intercept = TacticalUiInput.intercept((Document) (Object) this, position);
        if (intercept != null) result.setReturnValue(intercept);
    }
}
