import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { MessageCircle, X, Send } from 'lucide-react';

// Set VITE_MCP_SERVER_URL in the portfolio site's .env to the deployed
// Hugging Face Space URL, e.g. https://maniram1-portfolio-mcp-server.hf.space
const MCP_SERVER_URL = import.meta.env.VITE_MCP_SERVER_URL || 'http://localhost:8081';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

const WELCOME_MESSAGE: ChatMessage = {
  role: 'assistant',
  content: "Hi! I'm Maniram's portfolio assistant. Ask me about his experience, projects, or skills.",
};

const ChatWidget = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [pastHero, setPastHero] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([WELCOME_MESSAGE]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Silently wake the Hugging Face server as soon as the page loads, so
  // it's already warm by the time the visitor opens the chat.
  useEffect(() => {
    fetch(`${MCP_SERVER_URL}/api/health`).catch(() => {
      // Cold start or offline — the chat itself will surface this if it matters.
    });
  }, []);

  // The hero's cinematic intro already fills its entire bottom edge (name/
  // subtitle bottom-left, play/mute controls bottom-right, scroll hint
  // bottom-center) — there's no corner to dock a floating launcher there
  // without covering something. Simplest robust fix: only show the launcher
  // once the visitor has scrolled past the hero, same corner (bottom-right)
  // as every other floating action button anywhere.
  useEffect(() => {
    let rafId = 0;
    const check = () => {
      rafId = 0;
      setPastHero(window.scrollY > window.innerHeight * 0.6);
    };
    const onScroll = () => {
      if (rafId) return;
      rafId = requestAnimationFrame(check);
    };
    check();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', onScroll);
      if (rafId) cancelAnimationFrame(rafId);
    };
  }, []);

  const showLauncher = pastHero || isOpen;

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading]);

  const sendMessage = async () => {
    const question = input.trim();
    if (!question || isLoading) return;

    setMessages((prev) => [...prev, { role: 'user', content: question }]);
    setInput('');
    setError(null);
    setIsLoading(true);

    try {
      const response = await fetch(`${MCP_SERVER_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      });

      if (!response.ok) throw new Error(`Request failed with ${response.status}`);

      const data = await response.json();
      setMessages((prev) => [...prev, { role: 'assistant', content: data.answer }]);
    } catch {
      setError('Server is waking up, try again in 30 seconds.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') sendMessage();
  };

  return (
    <>
      <AnimatePresence>
        {showLauncher && (
          <motion.button
            type="button"
            onClick={() => setIsOpen((v) => !v)}
            aria-label={isOpen ? 'Close chat' : 'Open chat'}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.8 }}
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            className="fixed bottom-6 right-6 z-50 flex h-14 w-14 items-center justify-center
                       rounded-full bg-gradient-to-r from-amber-600 to-orange-600
                       text-white shadow-lg hover:shadow-amber-500/25 transition-shadow duration-300"
          >
            {isOpen ? <X className="h-6 w-6" /> : <MessageCircle className="h-6 w-6" />}
          </motion.button>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0, y: 16, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 16, scale: 0.97 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
            className="fixed bottom-24 right-6 z-50 flex h-[520px] max-h-[70vh] w-[380px]
                       max-w-[calc(100vw-3rem)] flex-col overflow-hidden rounded-2xl
                       border border-gray-700/50 bg-[#070707] shadow-2xl"
          >
            <div className="flex items-center justify-between border-b border-gray-800 px-4 py-3">
              <p className="font-display text-sm font-semibold text-white">
                Ask about Maniram
              </p>
              <button
                type="button"
                onClick={() => setIsOpen(false)}
                aria-label="Close chat"
                className="text-gray-400 hover:text-white transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
              {messages.map((message, index) => (
                <div
                  key={index}
                  className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[85%] rounded-lg px-3 py-2 text-sm leading-relaxed ${
                      message.role === 'user'
                        ? 'bg-gradient-to-r from-amber-600 to-orange-600 text-white'
                        : 'bg-gray-800/50 border border-gray-700/50 text-gray-100'
                    }`}
                  >
                    {message.content}
                  </div>
                </div>
              ))}

              {isLoading && (
                <div className="flex justify-start">
                  <div className="flex items-center gap-1 rounded-lg border border-gray-700/50 bg-gray-800/50 px-3 py-2">
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400 [animation-delay:0ms]" />
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400 [animation-delay:150ms]" />
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400 [animation-delay:300ms]" />
                  </div>
                </div>
              )}

              {error && (
                <p className="text-center text-xs text-amber-400/90">{error}</p>
              )}

              <div ref={messagesEndRef} />
            </div>

            <div className="flex items-center gap-2 border-t border-gray-800 px-3 py-3">
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Ask a question..."
                disabled={isLoading}
                className="flex-1 rounded-lg border border-gray-700/50 bg-gray-800/50 px-3 py-2
                           text-sm text-white placeholder:text-gray-500 outline-none
                           focus:border-amber-500/50 disabled:opacity-60"
              />
              <button
                type="button"
                onClick={sendMessage}
                disabled={isLoading || !input.trim()}
                aria-label="Send message"
                className="flex h-9 w-9 items-center justify-center rounded-lg
                           bg-gradient-to-r from-amber-600 to-orange-600 text-white
                           transition-opacity duration-200 disabled:opacity-40"
              >
                <Send className="h-4 w-4" />
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
};

export default ChatWidget;
