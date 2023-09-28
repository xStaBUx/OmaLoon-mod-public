package omaloon.content;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import mindustry.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.graphics.*;
import mindustry.type.*;

import static arc.graphics.g2d.Draw.*;
import static mindustry.content.StatusEffects.*;

public class OlStatusEffects {
    public static StatusEffect dalanied;

    public static void load(){
        dalanied = new StatusEffect("dalanied"){{
            color = Color.valueOf("5e929d");

            speedMultiplier = 0.8f;
            buildSpeedMultiplier = 0.8f;

            effect = new Effect(80f, ef -> {
                color(Color.valueOf("5e929d"));
                alpha(Mathf.clamp(ef.fin() * 2f));

                Fill.circle(ef.x, ef.y, ef.fout());
            }).layer(Layer.debris);
            effectChance = 0.1f;

            init(() -> {
                affinity(shocked, (unit, result, time) -> {
                    unit.damagePierce(transitionDamage);

                    if(unit.team == Vars.state.rules.waveTeam){
                        Events.fire(EventType.Trigger.shock);
                    }
                });

                opposite(burning, melting);
            });
        }};
    }
}
