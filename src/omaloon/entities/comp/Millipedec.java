package omaloon.entities.comp;

import arc.func.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import ent.anno.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import omaloon.gen.Millipedec;
import omaloon.type.*;
import omaloon.utils.*;

@SuppressWarnings({"unused", "UnnecessaryReturnStatement"})
@Annotations.EntityComponent
abstract class MillipedeComp implements Unitc {
    private static Unit last;
    transient Unit head, parent, child;
    transient float layer = 0f, scanTime = 0f;
    transient byte weaponIdx = 0;
    transient boolean removing = false, saveAdd = false;

    protected float splitHealthDiv = 1f;
    protected float regenTime = 0f;
    protected float waitTime = 0f;

    @Annotations.SyncLocal public int childId = -1, headId = -1;
    @Annotations.Import UnitType type;
    @Annotations.Import float healthMultiplier, health, x, y, elevation, rotation;
    @Annotations.Import boolean dead;
    @Annotations.Import WeaponMount[] mounts;
    @Annotations.Import Team team;

    @Override
    public boolean serialize(){
        return isHead();
    }

    boolean isHead(){
        return parent == null || head == null || head == self();
    }

    boolean isTail(){
        return child == null;
    }

    @Override
    @Annotations.Replace
    public TextureRegion icon(){
        GlasmoreUnitType uType = (GlasmoreUnitType)type;
        //if(isTail()) return uType.tailOutline;
        //if(!isHead()) return uType.segmentOutline;
        return type.fullIcon;
    }

    private void connect(Millipedec other){
        if(isHead() && other.isTail()){
            float z = other.layer() + 1f;
            distributeActionBack(u -> {
                u.layer(u.layer() + z);
                u.head(other.head());
            });
            other.child(self());
            parent = (Unit)other;
            head = other.head();
            setupWeapons(type);
            ((GlasmoreUnitType)type).chainSound.at(self());
            if(controller() instanceof Player){
                UnitController con = controller();
                other.head().controller(con);
                con.unit(other.head());
                controller(type.createController(self()));
            }
        }
    }

    int countFoward(){
        Millipedec current = self();
        int num = 0;
        while(current != null && current.parent() != null){
            if(current.parent() instanceof Millipedec){
                num++;
                current = (Millipedec)current.parent();
            }else{
                current = null;
            }
        }
        return num;
    }

    int countBackward(){
        Millipedec current = self();
        int num = 0;
        while(current != null && current.child() != null){
            if(current.child() instanceof Millipedec){
                num++;
                current = (Millipedec)current.child();
            }else{
                current = null;
            }
        }
        return num;
    }

    @Annotations.MethodPriority(-1)
    @Override
    @Annotations.BreakAll
    public void controller(UnitController next){
        if(next instanceof Player && head != null && !isHead()){
            head.controller(next);
            return;
        }
    }

    @Annotations.MethodPriority(100)
    @Override
    public void read(Reads read){
        if(read.bool()){
            saveAdd = true;
            int seg = read.s();
            Millipedec current = self();
            for(int i = 0; i < seg; i++){
                Unit u = type.constructor.get();
                Millipedec w = (Millipedec)u;
                current.child(u);
                w.parent((Unit)current);
                w.head(self());
                w.layer(i);
                w.weaponIdx(read.b());
                u.read(read);
                current = w;
            }
        }
    }

    @Annotations.MethodPriority(100)
    @Override
    public void write(Writes write){
        write.bool(isHead());
        if(isHead()){
            Millipedec ch = (Millipedec)child;
            int amount = 0;
            while(ch != null){
                amount++;
                ch = (Millipedec)ch.child();
            }
            write.s(amount);

            ch = (Millipedec)child;
            while(ch != null){
                write.b(weaponIdx);
                ch.write(write);
                ch = (Millipedec)ch.child();
            }
        }
    }

    @Annotations.Replace
    @Override
    public boolean isAI(){
        if(head != null && !isHead()) return head.isAI();
        return controller() instanceof AIController;
    }

    @Annotations.Replace
    @Annotations.MethodPriority(-2)
    @Override
    @Annotations.BreakAll
    public void damage(float amount){
        if(!isHead() && head != null && !((GlasmoreUnitType)type).splittable){
            head.damage(amount);
            return;
        }
    }

    @Annotations.MethodPriority(-1)
    @Override
    @Annotations.BreakAll
    public void heal(float amount){
        if(!isHead() && head != null && !((GlasmoreUnitType)type).splittable){
            head.heal(amount);
            return;
        }
    }

    <T extends Unit & Millipedec> void distributeActionBack(Cons<T> cons){
        T current = as();
        cons.get(current);
        while(current.child() != null){
            cons.get(current.child().as());
            current = current.child().as();
        }
    }

    <T extends Unit & Millipedec> void distributeActionForward(Cons<T> cons){
        T current = as();
        cons.get(current);
        while(current.parent() != null){
            cons.get(current.parent().as());
            current = current.parent().as();
        }
    }

    @Annotations.Replace
    @Override
    public int cap(){
        int max = Math.max(((GlasmoreUnitType)type).maxSegments, ((GlasmoreUnitType)type).segmentLength);
        return Units.getCap(team) * max;
    }

    @Annotations.Replace
    @Override
    public float speed(){
        if(!isHead()) return 0f;
        float strafePenalty = isGrounded() || !isPlayer() ? 1f : Mathf.lerp(1f, type.strafePenalty, Angles.angleDist(vel().angle(), rotation) / 180f);
        float boost = Mathf.lerp(1f, type.canBoost ? type.boostMultiplier : 1f, elevation);
        return type.speed * strafePenalty * boost * floorSpeedMultiplier();
    }

    @Override
    public void update(){
        GlasmoreUnitType uType = (GlasmoreUnitType)type;
        if(uType.splittable && isTail() && uType.regenTime > 0f){
            int forward = countFoward();
            if(forward < Math.max(uType.maxSegments, uType.segmentLength)){
                regenTime += Time.delta;
                if(regenTime >= uType.regenTime){
                    regenTime = 0f;
                    Unit unit;
                    if((unit = addTail()) != null){
                        health /= 2f;
                        unit.health = health;
                        ((GlasmoreUnitType)type).chainSound.at(self());
                    }
                }
            }
        }else{
            regenTime = 0f;
        }
        if(isTail() && waitTime > 0){
            waitTime -= Time.delta;
        }
        if(!uType.splittable){
            if(!isHead()) health = head.health;
            if((isHead() && isAdded()) || (head != null && head.isAdded())){
                Millipedec t = (Millipedec)child;
                while(t != null && !t.isAdded()){
                    t.add();
                    t = (Millipedec)t.child();
                }
            }
        }
        if(uType.splittable && (parent != null || child != null) && dead){
            destroy();
        }
    }

    @Annotations.Wrap(value = "update()", block = Boundedc.class)
    boolean updateBounded(){
        return isHead();
    }

    @Annotations.Insert(value = "update()", block = Statusc.class)
    private void updateHealthDiv(){
        healthMultiplier /= splitHealthDiv;
    }

    Unit addTail(){
        if(!isTail()) return null;
        Unit tail = type.constructor.get();
        tail.team = team;
        tail.setType(type);
        tail.ammo = type.ammoCapacity;
        tail.elevation = type.flying ? 1f : 0;
        tail.heal();

        GlasmoreUnitType uType = (GlasmoreUnitType)type;
        if(tail instanceof Millipedec){
            float z = layer + 1f;
            Tmp.v1.trns(rotation() + 180f, uType.segmentOffset).add(self());
            tail.set(Tmp.v1);
            ((Millipedec)tail).layer(z);
            ((Millipedec)tail).head(head);
            ((Millipedec)tail).parent(self());
            child = tail;
            tail.setupWeapons(uType);
            tail.add();
        }
        return tail;
    }

    @Annotations.Insert("update()")
    private void updatePost(){
        if(isHead()){
            GlasmoreUnitType uType = (GlasmoreUnitType)type;
            last = self();
            distributeActionBack(u -> {
                if(u == self()) return;

                float offset = self() == last ? uType.headOffset : 0f;
                Tmp.v1.trns(last.rotation + 180f, (uType.segmentOffset / 2f) + offset).add(last);

                float rdx = u.deltaX - last.deltaX;
                float rdy = u.deltaY - last.deltaY;

                float angTo = !uType.preventDrifting || (last.deltaLen() > 0.001f && (rdx * rdx) + (rdy * rdy) > 0.00001f) ? u.angleTo(Tmp.v1) : u.rotation;

                u.rotation = angTo - (OlUtils.angleDistSigned(angTo, last.rotation, uType.angleLimit) * (1f - uType.anglePhysicsSmooth));
                u.trns(Tmp.v3.trns(u.rotation, last.deltaLen()));
                Tmp.v2.trns(u.rotation, uType.segmentOffset / 2f).add(u);

                Tmp.v2.sub(Tmp.v1).scl(Mathf.clamp(uType.jointStrength * Time.delta));

                Unit n = u;
                int cast = uType.segmentCast;
                while(cast > 0 && n != null){
                    float scl = cast / (float)uType.segmentCast;
                    n.set(n.x - (Tmp.v2.x * scl), n.y - (Tmp.v2.y * scl));
                    n.updateLastPosition();
                    n = ((Millipedec)n).child();
                    cast--;
                }

                float nextHealth = (last.health() + u.health()) / 2f;
                if(!Mathf.equal(nextHealth, last.health(), 0.0001f)) last.health(Mathf.lerpDelta(last.health(), nextHealth, uType.healthDistribution));
                if(!Mathf.equal(nextHealth, u.health(), 0.0001f)) u.health(Mathf.lerpDelta(u.health(), nextHealth, uType.healthDistribution));

                Millipedec wrm = ((Millipedec)last);
                float nextHealthDv = (wrm.splitHealthDiv() + u.splitHealthDiv()) / 2f;
                if(!Mathf.equal(nextHealth, wrm.splitHealthDiv(), 0.0001f)) wrm.splitHealthDiv(Mathf.lerpDelta(wrm.splitHealthDiv(), nextHealthDv, uType.healthDistribution));
                if(!Mathf.equal(nextHealth, u.splitHealthDiv(), 0.0001f)) u.splitHealthDiv(Mathf.lerpDelta(u.splitHealthDiv(), nextHealthDv, uType.healthDistribution));
                last = u;
            });
            scanTime += Time.delta;
            if(scanTime >= 5f && uType.chainable){
                Tmp.v1.trns(rotation(), uType.segmentOffset / 2f).add(self());
                Tmp.r1.setCentered(Tmp.v1.x, Tmp.v1.y, hitSize());
                Units.nearby(Tmp.r1, u -> {
                    if(u.team == team && u.type == type && u instanceof Millipedec w && w.head() != self() && w.isTail() && w.countFoward() + countBackward() < uType.maxSegments && w.waitTime() <= 0f && within(u, uType.segmentOffset) && OlUtils.angleDist(rotation(), angleTo(u)) < uType.angleLimit){
                        connect(w);
                    }
                });
                scanTime = 0f;
            }
        }
    }

    @Annotations.Replace
    @Override
    public void wobble(){

    }

    @Annotations.MethodPriority(-1)
    @Override
    @Annotations.BreakAll
    public void setupWeapons(UnitType def){
        GlasmoreUnitType uType = (GlasmoreUnitType)def;
        if(!isHead()){
            //Seq<Weapon> seq = uType.segWeapSeq;
            Seq<Weapon> seq = uType.segmentWeapons[weaponIdx];
            mounts = new WeaponMount[seq.size];
            for(int i = 0; i < mounts.length; i++){
                mounts[i] = seq.get(i).mountType.get(seq.get(i));
            }
            return;
        }
    }

    @Override
    public void afterSync(){
        if(headId != -1 && head == null){
            Unit h = Groups.unit.getByID(headId);
            if(h instanceof Millipedec wc){
                head = h;
                headId = -1;
            }
        }
        if(childId != -1 && child == null){
            Unit c = Groups.unit.getByID(childId);
            if(c instanceof Millipedec wc){
                child = c;
                wc.parent(self());
                childId = -1;
            }
        }
    }

    @Override
    @Annotations.BreakAll
    public void remove(){
        GlasmoreUnitType uType = (GlasmoreUnitType)type;
        if(uType.splittable){
            if(child != null && parent != null) uType.splitSound.at(x(), y());
            if(child != null){
                var wc = (Unit & Millipedec)child;
                float z = 0f;
                while(wc != null){
                    wc.layer(z++);
                    wc.splitHealthDiv(wc.splitHealthDiv() * 2f);
                    wc.head(child);
                    if(wc.isTail()) wc.waitTime(5f * 60f);
                    wc = (Unit & Millipedec)wc.child();
                }
            }
            if(parent != null){
                Millipedec wp = ((Millipedec)parent);
                distributeActionForward(u -> {
                    if(u != self()){
                        u.splitHealthDiv(u.splitHealthDiv() * 2f);
                    }
                });
                wp.child(null);
                wp.waitTime(5f * 60f);
            }
            parent = null;
            child = null;
        }
        if(!isHead() && !uType.splittable && !removing){
            head.remove();
            return;
        }
        if(isHead() && !uType.splittable){
            distributeActionBack(u -> {
                if(u != self()){
                    u.removing(true);
                    u.remove();
                    u.removing(false);
                }
            });
        }
    }

    @Override
    public void add(){
        GlasmoreUnitType uType = (GlasmoreUnitType)type;
        Unit current = self();
        if(isHead()){
            if(saveAdd){
                var seg = (Unit & Millipedec)child;
                while(seg != null){
                    seg.add();
                    seg = (Unit & Millipedec)seg.child();
                }
                saveAdd = false;
                return;
            }
            float[] rot = {rotation() + uType.angleLimit};
            Tmp.v1.trns(rot[0] + 180f, uType.segmentOffset + uType.headOffset).add(self());
            distributeActionBack(u -> {
                if(u != self()){
                    u.x = Tmp.v1.x;
                    u.y = Tmp.v1.y;
                    u.rotation = rot[0];

                    rot[0] += uType.angleLimit;
                    Tmp.v2.trns(rot[0] + 180f, uType.segmentOffset);
                    Tmp.v1.add(Tmp.v2);

                    u.add();
                }
            });
        }
    }
}
