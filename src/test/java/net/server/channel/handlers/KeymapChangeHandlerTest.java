package net.server.channel.handlers;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Magician;
import constants.skills.Priest;
import constants.skills.SuperGM;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import testutil.HandlerTest;
import testutil.Packets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeymapChangeHandlerTest extends HandlerTest {
    private static final int KEY = 16;
    private final KeymapChangeHandler handler = new KeymapChangeHandler();

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"BEGINNER", "WARRIOR"})
    void allowsLearnedSkillsFromAPreviousJobAfterRebirth(Job currentJob) {
        Skill skill = new Skill(Priest.HOLY_SYMBOL);
        when(chr.getJob()).thenReturn(currentJob);
        when(chr.getReborns()).thenReturn(1);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 30);
        when(chr.getSkillExpiration(skill)).thenReturn(-1L);

        bindSkill(skill);

        verifyBinding(Priest.HOLY_SYMBOL);
    }

    @Test
    void allowsInheritedSkillsBeforeTheirExpiration() {
        Skill skill = new Skill(Priest.HOLY_SYMBOL);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getReborns()).thenReturn(1);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 30);
        when(chr.getSkillExpiration(skill)).thenReturn(Long.MAX_VALUE);

        bindSkill(skill);

        verifyBinding(Priest.HOLY_SYMBOL);
    }

    @Test
    void rejectsExpiredInheritedSkillsBeforeTheExpirationTaskRemovesThem() {
        Skill skill = new Skill(Priest.HOLY_SYMBOL);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getReborns()).thenReturn(1);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 30);
        when(chr.getSkillExpiration(skill)).thenReturn(1L);

        bindSkill(skill);

        verify(chr, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void rejectsUnlearnedSkillsFromOtherJobsAfterRebirth() {
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getReborns()).thenReturn(1);

        bindSkill(new Skill(Priest.HOLY_SYMBOL));

        verify(chr, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void rejectsCrossJobSkillsWithoutRebirth() {
        Skill skill = new Skill(Priest.HOLY_SYMBOL);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        lenient().when(chr.getSkillLevel(skill)).thenReturn((byte) 30);

        bindSkill(skill);

        verify(chr, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void preservesCurrentJobBindingsWithoutRebirth() {
        when(chr.getJob()).thenReturn(Job.MAGICIAN);

        bindSkill(new Skill(Magician.MAGIC_GUARD));

        verifyBinding(Magician.MAGIC_GUARD);
    }

    @Test
    void rebirthDoesNotPermitGmSkillsForPlayers() {
        Skill skill = new Skill(SuperGM.HIDE);
        lenient().when(chr.getReborns()).thenReturn(1);
        lenient().when(chr.getSkillLevel(skill)).thenReturn((byte) 1);

        bindSkill(skill);

        verify(chr, never()).changeKeybinding(anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bannedSkillsRemainUnavailableEvenWhenLearnedOrGm(boolean gm) {
        Skill skill = new Skill(1010); // Dojo skill: never a permitted keybinding.
        lenient().when(chr.isGM()).thenReturn(gm);
        lenient().when(chr.getReborns()).thenReturn(1);
        lenient().when(chr.getSkillLevel(skill)).thenReturn((byte) 1);

        bindSkill(skill);

        verify(chr, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void preservesGmSkillBindingsForGms() {
        when(chr.isGM()).thenReturn(true);

        bindSkill(new Skill(SuperGM.HIDE));

        verifyBinding(SuperGM.HIDE);
    }

    @Test
    void unknownSkillDoesNotPreventLaterValidBindingInThePacket() {
        Skill skill = new Skill(Magician.MAGIC_GUARD);
        when(chr.getJob()).thenReturn(Job.MAGICIAN);
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeInt(0);
            out.writeInt(2);
            out.writeInt(KEY);
            out.writeByte(1);
            out.writeInt(Integer.MAX_VALUE);
            out.writeInt(KEY);
            out.writeByte(1);
            out.writeInt(skill.getId());
        });

        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(skill.getId())).thenReturn(skill);
            handler.handlePacket(packet, client);
        }

        verifyBinding(Magician.MAGIC_GUARD);
        verify(chr, never()).changeKeybinding(anyInt(), argThat(binding -> binding.getAction() == Integer.MAX_VALUE));
    }

    private void bindSkill(Skill skill) {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(KEY);
            out.writeByte(1);
            out.writeInt(skill.getId());
        });
        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(skill.getId())).thenReturn(skill);
            handler.handlePacket(packet, client);
        }
    }

    private void verifyBinding(int skillId) {
        verify(chr).changeKeybinding(eq(KEY), argThat(binding -> binding.getType() == 1 && binding.getAction() == skillId));
    }
}
