import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import Tag from "@/components/common/Tag";
import SectionHeader from "@/components/common/SectionHeader";
import { ArrowRightIcon } from "@/components/icons/Icon";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./CourseDetailPage.module.css";
import { knowpostService } from "@/services/knowpostService";
import { useAuth } from "@/context/AuthContext";
import type { KnowpostDetailResponse } from "@/types/knowpost";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import LikeFavBar from "@/components/common/LikeFavBar";
import FollowButton from "@/components/common/FollowButton";

const CourseDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { tokens, user } = useAuth();
  const [detail, setDetail] = useState<KnowpostDetailResponse | null>(null);
  const [activeImage, setActiveImage] = useState(0);
  const [contentText, setContentText] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewIndex, setPreviewIndex] = useState(0);
  const rowRef = useRef<HTMLDivElement | null>(null);
  const [visibleCount, setVisibleCount] = useState<number>(0);
  const [contentError, setContentError] = useState<string | null>(null);
  const previewBoxRef = useRef<HTMLDivElement | null>(null);
  const [showNavLeft, setShowNavLeft] = useState(false);
  const [showNavRight, setShowNavRight] = useState(false);
  const [isTouch, setIsTouch] = useState(false);
  // RAG 问答状态
  const [ragQuestion, setRagQuestion] = useState<string>("");
  const [ragAnswer, setRagAnswer] = useState<string>("");
  const [ragHistory, setRagHistory] = useState<{role: string, content: string}[]>([]);
  const [ragSessionId, setRagSessionId] = useState<string>(() => {
    // 从 localStorage 恢复该文章的会话 ID
    return id ? localStorage.getItem(`rag_session_${id}`) || "" : "";
  });
  const [ragLoading, setRagLoading] = useState<boolean>(false);
  const [ragError, setRagError] = useState<string | null>(null);
  const ragAbortRef = useRef<AbortController | null>(null);
  const [ragTopK, setRagTopK] = useState<number>(5);
  const [ragMaxTokens, setRagMaxTokens] = useState<number>(1024);
  // 从头像 URL 推断作者 ID（示例：.../avatars/3-xxxx.jpg → 3）
  const parseAvatarUserId = (url?: string): number | undefined => {
    if (!url) return undefined;
    const m = url.match(/\/avatars\/(\d+)-/);
    return m ? Number(m[1]) : undefined;
  };

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!id) return;
      setError(null);
      try {
        const resp = await knowpostService.detail(id, tokens?.accessToken ?? undefined);
        if (cancelled) return;
        setDetail(resp);
        setActiveImage(0);
        // 异步加载正文内容
        if (resp.contentUrl) {
          const allowAnonymous = resp.visible === "public";
          if (allowAnonymous || !!tokens?.accessToken) {
            try {
              const text = await fetch(resp.contentUrl, { credentials: "omit" }).then(r => {
                if (!r.ok) throw new Error(`HTTP ${r.status}`);
                return r.text();
              });
              if (!cancelled) {
                setContentText(text);
                setContentError(null);
              }
            } catch (e) {
              if (!cancelled) setContentError("正文暂不可读，可能为非公开或跨域受限");
            }
          } else {
            setContentError("该知文非公开，请登录后查看正文");
          }
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : "加载失败";
        if (!cancelled) setError(msg);
      }
    };
    run();
    return () => { cancelled = true; };
  }, [id, tokens?.accessToken]);

  // 计算一行可展示的图片数量
  useEffect(() => {
    const calc = () => {
      const el = rowRef.current;
      if (!el) return;
      const width = el.clientWidth;
      const itemW = 180;
      const gap = 12;
      const count = Math.max(1, Math.floor((width + gap) / (itemW + gap)));
      setVisibleCount(count);
    };
    calc();
    window.addEventListener("resize", calc);
    return () => window.removeEventListener("resize", calc);
  }, [detail?.images]);

  useEffect(() => {
    const touch = "ontouchstart" in window || navigator.maxTouchPoints > 0;
    setIsTouch(touch);
    if (touch) {
      setShowNavLeft(true);
      setShowNavRight(true);
    }
  }, []);

  const handlePreviewMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (isTouch) return;
    const el = previewBoxRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const threshold = Math.max(60, Math.min(120, rect.width * 0.08));
    setShowNavLeft(x < threshold);
    setShowNavRight(x > rect.width - threshold);
  };

  const handlePreviewMouseLeave = () => {
    if (isTouch) return;
    setShowNavLeft(false);
    setShowNavRight(false);
  };

  const openPreview = (index: number) => {
    setPreviewIndex(index);
    setPreviewOpen(true);
  };

  const prevImage = () => {
    if (!detail?.images?.length) return;
    setPreviewIndex((i) => (i - 1 + detail.images.length) % detail.images.length);
  };

  const nextImage = () => {
    if (!detail?.images?.length) return;
    setPreviewIndex((i) => (i + 1) % detail.images.length);
  };

  // 启动 RAG 流式问答（Redis 会话模式）
  const startRag = async () => {
    if (!id) return;
    const q = ragQuestion.trim();
    if (!q) return;
    if (detail && detail.visible !== “public”) {
      setRagError(“仅公开知文支持问答”);
      return;
    }
    setRagError(null);
    setRagAnswer(“”);
    if (ragAbortRef.current) {
      try { ragAbortRef.current.abort(); } catch {}
      ragAbortRef.current = null;
    }
    const controller = new AbortController();
    ragAbortRef.current = controller;
    setRagLoading(true);

    // 确保有 sessionId（无则创建新会话）
    let sid = ragSessionId;
    if (!sid) {
      try {
        const res = await fetch(`/api/v1/knowposts/${id}/qa/sessions`, { method: “POST” });
        const data = await res.json();
        sid = data.sessionId;
        setRagSessionId(sid);
        localStorage.setItem(`rag_session_${id}`, sid);
      } catch {
        setRagError(“创建会话失败”);
        setRagLoading(false);
        return;
      }
    }

    try {
      const url = `/api/v1/knowposts/${id}/qa/stream`;
      const resp = await fetch(url, {
        method: “POST”,
        headers: { “Content-Type”: “application/json” },
        body: JSON.stringify({
          question: q,
          sessionId: sid,
          topK: ragTopK,
          maxTokens: ragMaxTokens,
        }),
        signal: controller.signal,
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const reader = resp.body?.getReader();
      if (!reader) throw new Error(“No response body”);
      const decoder = new TextDecoder();
      let full = “”;
      let buffer = “”;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split(“\n”);
        buffer = lines.pop() || “”;
        for (const line of lines) {
          if (line.startsWith(“data:”)) {
            full += line.slice(5).trim();
          }
        }
        setRagAnswer(full);
      }
      // 更新本地历史展示
      setRagHistory((prev) => [
        ...prev,
        { role: “user”, content: q },
        { role: “assistant”, content: full || “(空回答)” },
      ]);
      setRagQuestion(“”);
    } catch (err: any) {
      if (err.name !== “AbortError”) {
        // 静默
      }
    } finally {
      setRagLoading(false);
      ragAbortRef.current = null;
    }
  };

  const stopRag = () => {
    if (ragAbortRef.current) {
      try { ragAbortRef.current.abort(); } catch {}
      ragAbortRef.current = null;
    }
    setRagLoading(false);
  };

  useEffect(() => {
    return () => {
      if (ragAbortRef.current) {
        try { ragAbortRef.current.abort(); } catch {}
        ragAbortRef.current = null;
      }
    };
  }, []);

  return (
    <AppLayout
      header={
        <MainHeader
          headline={detail?.title ?? ""}
          subtitle=""
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <article className={styles.detailCard}>
        {error ? <div style={{ color: "var(--color-danger)" }}>{error}</div> : null}
        {detail?.images?.length ? (
          <div ref={rowRef} className={styles.imageRow}>
            {(detail.images.slice(0, visibleCount)).map((src, idx) => {
              const isLastVisible = idx === visibleCount - 1 && detail.images.length > visibleCount;
              return (
                <div key={src + idx} className={styles.imageItem} onClick={() => openPreview(idx)}>
                  <img className={styles.image} src={src} alt={detail.title} />
                  {isLastVisible ? (
                    <div className={styles.moreBadge}>+{detail.images.length - visibleCount}</div>
                  ) : null}
                </div>
              );
            })}
            {detail.images.length <= visibleCount
              ? null
              : null}
          </div>
        ) : null}
        <div className={styles.titleBlock}>
          <div className={styles.titleRow}></div>
          <div className={styles.meta}>
            {detail?.authorAvatar ? (
              <img className={styles.authorAvatar} src={detail.authorAvatar} alt={detail.authorNickname} />
            ) : null}
            <span className={styles.authorName}>{detail?.authorNickname ?? ""}</span>
            {(() => {
              const derivedId = detail?.authorId ?? parseAvatarUserId(detail?.authorAvatar);
              const isSelf = (derivedId && user?.id === derivedId) || (!!detail?.authorNickname && !!user?.nickname && detail.authorNickname === user.nickname);
              return derivedId && !isSelf ? <FollowButton targetUserId={derivedId} /> : null;
            })()}
          </div>
          <div className={styles.tagList}>
            {(detail?.tags ?? []).map(tag => (
              <Tag key={tag}>#{tag}</Tag>
            ))}
          </div>
          <div className={styles.meta}>
            {detail?.publishTime ? (
              <span>{new Date(detail.publishTime).toLocaleDateString("zh-CN")}</span>
            ) : null}
          </div>
          <div className={styles.bottomBar}>
            {detail ? (
              <LikeFavBar
                entityId={detail.id}
                initialCounts={{ like: detail.likeCount ?? 0, fav: detail.favoriteCount ?? 0 }}
                initialState={{ liked: detail.liked, faved: detail.faved }}
              />
            ) : null}
          </div>
        </div>

        <SectionHeader title="内容正文" subtitle="" />

        <div className={styles.contentRow}>
          <div className={styles.contentMain}>
            <div className={`${styles.body} ${styles.markdown}`}>
              {contentText ? (
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    a: ({ node, ...props }) => (
                      <a {...props} target="_blank" rel="noreferrer" />
                    ),
                    img: ({ node, ...props }) => (
                      <img {...props} style={{ maxWidth: "100%", borderRadius: 12 }} />
                    ),
                  }}
                >
                  {contentText}
                </ReactMarkdown>
              ) : (
                "暂无内容"
              )}
            </div>
            {contentError ? (
              <div style={{ color: "var(--color-danger)" }}>{contentError} {detail?.contentUrl ? (<a href={detail.contentUrl} target="_blank" rel="noreferrer">查看原文</a>) : null}</div>
            ) : null}
          </div>

          <aside className={styles.ragPanel}>
            <div className={styles.ragBody}>
              {/* 对话历史 */}
              <div className={styles.ragHistory}>
                {ragHistory.map((msg, i) => (
                  <div key={i} className={msg.role === “user” ? styles.ragMsgUser : styles.ragMsgAssistant}>
                    <div className={styles.ragMsgLabel}>{msg.role === “user” ? “你” : “AI”}</div>
                    <div className={`${styles.markdown} ${styles.ragMsgContent}`}>
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          a: ({ node, ...props }) => <a {...props} target=”_blank” rel=”noreferrer” />,
                        }}
                      >
                        {msg.content}
                      </ReactMarkdown>
                    </div>
                  </div>
                ))}
              </div>
              {/* 当前回答（流式） */}
              {ragLoading && (
                <div className={styles.ragMsgAssistant}>
                  <div className={styles.ragMsgLabel}>AI</div>
                  <div className={`${styles.markdown} ${styles.ragMsgContent}`}>
                    {ragAnswer ? (
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {ragAnswer}
                      </ReactMarkdown>
                    ) : (
                      <span style={{ color: “var(--color-text-muted)” }}>思考中…</span>
                    )}
                  </div>
                </div>
              )}
              {ragError ? (
                <div style={{ color: “var(--color-danger)” }}>{ragError}</div>
              ) : null}
              {/* 输入区 */}
              <textarea
                className={styles.ragTextarea}
                placeholder=”围绕本知文提问，例如：这篇知文的核心观点是什么？”
                value={ragQuestion}
                onChange={(e) => setRagQuestion(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === “Enter” && !e.shiftKey) {
                    e.preventDefault();
                    startRag();
                  }
                }}
              />
              <div className={styles.ragControls}>
                <button
                  type=”button”
                  className={`${styles.ragBtn} ${styles.ragBtnPrimary}`}
                  onClick={startRag}
                  disabled={ragLoading || !ragQuestion.trim()}
                >
                  {ragLoading ? “生成中…” : “发送”}
                </button>
                <button type=”button” className={`${styles.ragBtn} ${styles.ragBtnGhost}`} onClick={stopRag} disabled={!ragLoading}>
                  停止
                </button>
                {ragHistory.length > 0 && (
                  <button type=”button” className={`${styles.ragBtn} ${styles.ragBtnGhost}`} onClick={() => {
                    setRagSessionId(“”);
                    setRagHistory([]);
                    setRagAnswer(“”);
                    if (id) localStorage.removeItem(`rag_session_${id}`);
                  }}>
                    新对话
                  </button>
                )}
              </div>
            </div>
          </aside>
        </div>

        {previewOpen && detail?.images?.length ? (
          <div className={styles.previewOverlay} onClick={() => setPreviewOpen(false)}>
            <div
              className={styles.previewBox}
              ref={previewBoxRef}
              onMouseMove={handlePreviewMouseMove}
              onMouseLeave={handlePreviewMouseLeave}
              onClick={(e) => e.stopPropagation()}
            >
              <img className={styles.previewImage} src={detail.images[previewIndex]} alt={detail.title} />
              <button
                type="button"
                className={`${styles.navButton} ${styles.navButtonLeft} ${showNavLeft ? styles.navButtonVisible : ""}`}
                onClick={(e) => { e.stopPropagation(); prevImage(); }}
                aria-label="上一张"
              >
                <ArrowRightIcon width={24} height={24} style={{ transform: "rotate(180deg)" }} />
              </button>
              <button
                type="button"
                className={`${styles.navButton} ${styles.navButtonRight} ${showNavRight ? styles.navButtonVisible : ""}`}
                onClick={(e) => { e.stopPropagation(); nextImage(); }}
                aria-label="下一张"
              >
                <ArrowRightIcon width={24} height={24} />
              </button>
              <button type="button" className={styles.closeButton} onClick={(e) => { e.stopPropagation(); setPreviewOpen(false); }} aria-label="关闭">✕</button>
            </div>
          </div>
        ) : null}
      </article>
    </AppLayout>
  );
};

export default CourseDetailPage;
