/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_OVERRIDE")

package net.mamoe.mirai.qqandroid.message

import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.QQ
import net.mamoe.mirai.event.subscribingGetAsync
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.OfflineMessageSource
import net.mamoe.mirai.message.data.OnlineMessageSource
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.ImMsgBody
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.MsgComm
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.SourceMsg
import net.mamoe.mirai.qqandroid.network.protocol.packet.EMPTY_BYTE_ARRAY
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.receive.OnlinePush
import net.mamoe.mirai.qqandroid.utils._miraiContentToString
import net.mamoe.mirai.qqandroid.utils.io.serialization.loadAs
import net.mamoe.mirai.qqandroid.utils.io.serialization.toByteArray
import net.mamoe.mirai.utils.MiraiExperimentalAPI


internal interface MessageSourceImpl {
    val sequenceId: Int

    var isRecalledOrPlanned: Boolean // // TODO: 2020/4/7 实现 isRecalledOrPlanned
}

internal suspend inline fun MessageSource.ensureSequenceIdAvailable() {
    if (this is MessageSourceToGroupImpl) {
        this.ensureSequenceIdAvailable()
    }
}

internal class MessageSourceFromFriendImpl(
    override val bot: Bot,
    val msg: MsgComm.Msg
) : OnlineMessageSource.Incoming.FromFriend(), MessageSourceImpl {
    override val sequenceId: Int get() = msg.msgHead.msgSeq
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    override val id: Int get() = msg.msgBody.richText.attr!!.random
    override val time: Int get() = msg.msgHead.msgTime
    override val originalMessage: MessageChain by lazy {
        msg.toMessageChain(
            bot,
            groupIdOrZero = 0,
            onlineSource = false
        )
    }
    override val target: Bot get() = bot
    override val sender: QQ get() = bot.getFriend(msg.msgHead.fromUin)

    private val elems by lazy {
        msg.msgBody.richText.elems.toMutableList().also {
            if (it.last().elemFlags2 == null) it.add(ImMsgBody.Elem(elemFlags2 = ImMsgBody.ElemFlags2()))
        }
    }

    internal fun toJceDataImplForFriend(): ImMsgBody.SourceMsg {
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(msg.msgHead.msgSeq),
            senderUin = msg.msgHead.fromUin,
            toUin = msg.msgHead.toUin,
            flag = 1,
            elems = msg.msgBody.richText.elems,
            type = 0,
            time = msg.msgHead.msgTime,
            pbReserve = SourceMsg.ResvAttr(
                origUids = id.toLong() and 0xFFFF_FFFF
            ).toByteArray(SourceMsg.ResvAttr.serializer()),
            srcMsg = MsgComm.Msg(
                msgHead = MsgComm.MsgHead(
                    fromUin = msg.msgHead.fromUin, // qq
                    toUin = msg.msgHead.toUin, // group
                    msgType = msg.msgHead.msgType, // 82?
                    c2cCmd = msg.msgHead.c2cCmd,
                    msgSeq = msg.msgHead.msgSeq,
                    msgTime = msg.msgHead.msgTime,
                    msgUid = id.toLong() and 0xFFFF_FFFF, // ok
                    // groupInfo = MsgComm.GroupInfo(groupCode = msg.msgHead.groupInfo.groupCode),
                    isSrcMsg = true
                ),
                msgBody = ImMsgBody.MsgBody(
                    richText = ImMsgBody.RichText(
                        elems = elems
                    )
                )
            ).toByteArray(MsgComm.Msg.serializer())
        )
    }
}

internal class MessageSourceFromTempImpl(
    override val bot: Bot,
    private val msg: MsgComm.Msg
) : OnlineMessageSource.Incoming.FromTemp(), MessageSourceImpl {
    override val sequenceId: Int get() = msg.msgHead.msgSeq
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    override val id: Int get() = msg.msgBody.richText.attr!!.random
    override val time: Int get() = msg.msgHead.msgTime
    override val originalMessage: MessageChain by lazy {
        msg.toMessageChain(
            bot,
            groupIdOrZero = 0,
            onlineSource = false
        )
    }
    override val target: Bot get() = bot
    override val sender: Member get() = with(msg.msgHead) {
        bot.getGroup(c2cTmpMsgHead!!.groupUin)[fromUin]
    }

    private val elems by lazy {
        msg.msgBody.richText.elems.toMutableList().also {
            if (it.last().elemFlags2 == null) it.add(ImMsgBody.Elem(elemFlags2 = ImMsgBody.ElemFlags2()))
        }
    }

    internal fun toJceDataImplForTemp(): ImMsgBody.SourceMsg {
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(msg.msgHead.msgSeq),
            senderUin = msg.msgHead.fromUin,
            toUin = msg.msgHead.toUin,
            flag = 1,
            elems = msg.msgBody.richText.elems,
            type = 0,
            time = msg.msgHead.msgTime,
            pbReserve = SourceMsg.ResvAttr(
                origUids = id.toLong() and 0xFFFF_FFFF
            ).toByteArray(SourceMsg.ResvAttr.serializer()),
            srcMsg = MsgComm.Msg(
                msgHead = MsgComm.MsgHead(
                    fromUin = msg.msgHead.fromUin, // qq
                    toUin = msg.msgHead.toUin, // group
                    msgType = msg.msgHead.msgType, // 82?
                    c2cCmd = msg.msgHead.c2cCmd,
                    msgSeq = msg.msgHead.msgSeq,
                    msgTime = msg.msgHead.msgTime,
                    msgUid = id.toLong() and 0xFFFF_FFFF, // ok
                    // groupInfo = MsgComm.GroupInfo(groupCode = msg.msgHead.groupInfo.groupCode),
                    isSrcMsg = true
                ),
                msgBody = ImMsgBody.MsgBody(
                    richText = ImMsgBody.RichText(
                        elems = elems
                    )
                )
            ).toByteArray(MsgComm.Msg.serializer())
        )
    }
}

internal class MessageSourceFromGroupImpl(
    override val bot: Bot,
    private val msg: MsgComm.Msg
) : OnlineMessageSource.Incoming.FromGroup(), MessageSourceImpl {
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    override val sequenceId: Int get() = msg.msgHead.msgSeq
    override val id: Int get() = msg.msgBody.richText.attr!!.random
    override val time: Int get() = msg.msgHead.msgTime
    override val originalMessage: MessageChain by lazy {
        msg.toMessageChain(
            bot,
            groupIdOrZero = group.id,
            onlineSource = false
        )
    }
    override val target: Bot get() = bot
    override val sender: Member
        get() = bot.getGroup(
            msg.msgHead.groupInfo?.groupCode
                ?: error("cannot find groupCode for MessageSourceFromGroupImpl. msg=${msg._miraiContentToString()}")
        ).getOrNull(msg.msgHead.fromUin)
            ?: error("cannot find member for MessageSourceFromGroupImpl. msg=${msg._miraiContentToString()}")


    fun toJceDataImplForGroup(): ImMsgBody.SourceMsg {
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(msg.msgHead.msgSeq),
            senderUin = msg.msgHead.fromUin,
            toUin = 0,
            flag = 1,
            elems = msg.msgBody.richText.elems,
            type = 0,
            time = msg.msgHead.msgTime,
            pbReserve = EMPTY_BYTE_ARRAY,
            srcMsg = EMPTY_BYTE_ARRAY
        )
    }
}

internal class OfflineMessageSourceImplByMsg( // from other sources' originalMessage
    val delegate: MsgComm.Msg,
    override val bot: Bot
) : OfflineMessageSource(), MessageSourceImpl {
    override val kind: Kind = if (delegate.msgHead.groupInfo != null) Kind.GROUP else Kind.FRIEND
    override val id: Int
        get() = delegate.msgHead.msgUid.toInt()
    override val time: Int
        get() = delegate.msgHead.msgTime
    override val fromId: Long
        get() = delegate.msgHead.fromUin
    override val targetId: Long
        get() = delegate.msgHead.groupInfo?.groupCode ?: delegate.msgHead.toUin
    override val originalMessage: MessageChain by lazy {
        delegate.toMessageChain(bot,
            groupIdOrZero = delegate.msgHead.groupInfo?.groupCode ?: 0,
            onlineSource = false,
            isTemp = delegate.msgHead.c2cTmpMsgHead != null
        )
    }
    override val sequenceId: Int
        get() = delegate.msgHead.msgSeq

    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
}

internal class OfflineMessageSourceImplBySourceMsg( // from others' quotation
    val delegate: ImMsgBody.SourceMsg,
    override val bot: Bot,
    groupIdOrZero: Long
) : OfflineMessageSource(), MessageSourceImpl {
    override val kind: Kind get() = if (delegate.srcMsg == null) Kind.GROUP else Kind.FRIEND

    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    override val sequenceId: Int
        get() = delegate.origSeqs?.first() ?: error("cannot find sequenceId")
    override val time: Int get() = delegate.time
    override val originalMessage: MessageChain by lazy { delegate.toMessageChain(bot, groupIdOrZero) }
    /*
    override val id: Long
        get() = (delegate.origSeqs?.firstOrNull()
            ?: error("cannot find sequenceId from ImMsgBody.SourceMsg")).toLong().shl(32) or
                delegate.pbReserve.loadAs(SourceMsg.ResvAttr.serializer()).origUids!!.and(0xFFFFFFFF)
    */

    override val id: Int
        get() = delegate.pbReserve.loadAs(SourceMsg.ResvAttr.serializer()).origUids?.toInt()
            ?: 0

    // override val sourceMessage: MessageChain get() = delegate.toMessageChain()
    override val fromId: Long get() = delegate.senderUin
    override val targetId: Long by lazy {
        when {
            groupIdOrZero != 0L -> groupIdOrZero
            delegate.toUin != 0L -> delegate.toUin
            delegate.srcMsg != null -> delegate.srcMsg.loadAs(MsgComm.Msg.serializer()).msgHead.toUin
            else -> 0/*error("cannot find targetId. delegate=${delegate._miraiContentToString()}, delegate.srcMsg=${
            kotlin.runCatching { delegate.srcMsg?.loadAs(MsgComm.Msg.serializer())?._miraiContentToString() }
                .fold(
                    onFailure = { "<error: ${it.message}>" },
                    onSuccess = { it }
                )
            }"
            )*/
        }
    }
}

internal class MessageSourceToFriendImpl(
    override val sequenceId: Int,
    override val id: Int,
    override val time: Int,
    override val originalMessage: MessageChain,
    override val sender: Bot,
    override val target: QQ
) : OnlineMessageSource.Outgoing.ToFriend(), MessageSourceImpl {
    override val bot: Bot
        get() = sender
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    private val elems by lazy {
        originalMessage.toRichTextElems(forGroup = false, withGeneralFlags = true)
    }

    fun toJceDataImplForFriend(): ImMsgBody.SourceMsg {
        val messageUid: Long = sequenceId.toLong().shl(32) or id.toLong().and(0xffFFffFF)
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(sequenceId),
            senderUin = fromId,
            toUin = targetId,
            flag = 1,
            elems = elems,
            type = 0,
            time = time,
            pbReserve = SourceMsg.ResvAttr(
                origUids = messageUid
            ).toByteArray(SourceMsg.ResvAttr.serializer()),
            srcMsg = MsgComm.Msg(
                msgHead = MsgComm.MsgHead(
                    fromUin = fromId, // qq
                    toUin = targetId, // group
                    msgType = 9, // 82?
                    c2cCmd = 11,
                    msgSeq = sequenceId,
                    msgTime = time,
                    msgUid = messageUid, // ok
                    // groupInfo = MsgComm.GroupInfo(groupCode = delegate.msgHead.groupInfo.groupCode),
                    isSrcMsg = true
                ),
                msgBody = ImMsgBody.MsgBody(
                    richText = ImMsgBody.RichText(
                        elems = elems.toMutableList().also {
                            if (it.last().elemFlags2 == null) it.add(ImMsgBody.Elem(elemFlags2 = ImMsgBody.ElemFlags2()))
                        }
                    )
                )
            ).toByteArray(MsgComm.Msg.serializer())
        )
    }
}

internal class MessageSourceToTempImpl(
    override val sequenceId: Int,
    override val id: Int,
    override val time: Int,
    override val originalMessage: MessageChain,
    override val sender: Bot,
    override val target: Member
) : OnlineMessageSource.Outgoing.ToTemp(), MessageSourceImpl {
    override val bot: Bot
        get() = sender
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    private val elems by lazy {
        originalMessage.toRichTextElems(forGroup = false, withGeneralFlags = true)
    }

    fun toJceDataImplForTemp(): ImMsgBody.SourceMsg {
        val messageUid: Long = sequenceId.toLong().shl(32) or id.toLong().and(0xffFFffFF)
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(sequenceId),
            senderUin = fromId,
            toUin = targetId,
            flag = 1,
            elems = elems,
            type = 0,
            time = time,
            pbReserve = SourceMsg.ResvAttr(
                origUids = messageUid
            ).toByteArray(SourceMsg.ResvAttr.serializer()),
            srcMsg = MsgComm.Msg(
                msgHead = MsgComm.MsgHead(
                    fromUin = fromId, // qq
                    toUin = targetId, // group
                    msgType = 9, // 82?
                    c2cCmd = 11,
                    msgSeq = sequenceId,
                    msgTime = time,
                    msgUid = messageUid, // ok
                    // groupInfo = MsgComm.GroupInfo(groupCode = delegate.msgHead.groupInfo.groupCode),
                    isSrcMsg = true
                ),
                msgBody = ImMsgBody.MsgBody(
                    richText = ImMsgBody.RichText(
                        elems = elems.toMutableList().also {
                            if (it.last().elemFlags2 == null) it.add(ImMsgBody.Elem(elemFlags2 = ImMsgBody.ElemFlags2()))
                        }
                    )
                )
            ).toByteArray(MsgComm.Msg.serializer())
        )
    }
}

internal class MessageSourceToGroupImpl(
    override val id: Int,
    override val time: Int,
    override val originalMessage: MessageChain,
    override val sender: Bot,
    override val target: Group
) : OnlineMessageSource.Outgoing.ToGroup(), MessageSourceImpl {
    override val bot: Bot
        get() = sender
    private val isRecalled: AtomicBoolean = atomic(false)
    override var isRecalledOrPlanned: Boolean
        get() = isRecalled.value
        set(value) {
            isRecalled.value = value
        }
    private val elems by lazy {
        originalMessage.toRichTextElems(forGroup = false, withGeneralFlags = true)
    }
    private lateinit var sequenceIdDeferred: Deferred<Int>
    override val sequenceId: Int get() = sequenceIdDeferred.getCompleted()

    @OptIn(MiraiExperimentalAPI::class)
    internal fun startWaitingSequenceId(coroutineScope: CoroutineScope) {
        sequenceIdDeferred =
            coroutineScope.subscribingGetAsync<OnlinePush.PbPushGroupMsg.SendGroupMessageReceipt, Int>(
                timeoutMillis = 3000
            ) {
                if (it.messageRandom == this@MessageSourceToGroupImpl.id) {
                    it.sequenceId
                } else null
            }
    }

    suspend fun ensureSequenceIdAvailable() {
        sequenceIdDeferred.join()
    }


    fun toJceDataImplForGroup(): ImMsgBody.SourceMsg {
        return ImMsgBody.SourceMsg(
            origSeqs = listOf(sequenceId),
            senderUin = fromId,
            toUin = Group.calculateGroupUinByGroupCode(targetId),
            flag = 1,
            elems = elems,
            type = 0,
            time = time,
            pbReserve = SourceMsg.ResvAttr(
                origUids = id.toLong() and 0xffFFffFF // id is actually messageRandom
            ).toByteArray(SourceMsg.ResvAttr.serializer()),
            srcMsg = MsgComm.Msg(
                msgHead = MsgComm.MsgHead(
                    fromUin = fromId, // qq
                    toUin = Group.calculateGroupUinByGroupCode(targetId), // group
                    msgType = 82, // 82?
                    c2cCmd = 1,
                    msgSeq = sequenceId,
                    msgTime = time,
                    msgUid = id.toLong() and 0xffFFffFF, // ok
                    groupInfo = MsgComm.GroupInfo(groupCode = targetId),
                    isSrcMsg = true
                ),
                msgBody = ImMsgBody.MsgBody(
                    richText = ImMsgBody.RichText(
                        elems = elems.toMutableList().also {
                            if (it.last().elemFlags2 == null) it.add(ImMsgBody.Elem(elemFlags2 = ImMsgBody.ElemFlags2()))
                        }
                    )
                )
            ).toByteArray(MsgComm.Msg.serializer())
        )
    }
}