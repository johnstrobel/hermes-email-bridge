import os
import imaplib
import email
from email.header import decode_header
import time
import threading
import collections
import requests
from bs4 import BeautifulSoup
from http.server import HTTPServer, BaseHTTPRequestHandler
import json

GMAIL_USER = os.getenv("GMAIL_USER")
GMAIL_APP_PASS = os.getenv("GMAIL_APP_PASS")

PROCESSED_EMAIL_IDS = set()
email_summaries = collections.deque(maxlen=5)  # newest first
summary_lock = threading.Lock()


class HermesHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/emails":
            with summary_lock:
                body = json.dumps(list(email_summaries)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass


def start_api_server():
    server = HTTPServer(("0.0.0.0", 5050), HermesHandler)
    server.serve_forever()


OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL_NAME = "hermes3"


def clean_email_body(html_or_text):
    soup = BeautifulSoup(html_or_text, "html.parser")
    for script in soup(["script", "style"]):
        script.extract()
    text = soup.get_text(separator="\n")
    lines = [line.strip() for line in text.splitlines()]
    return "\n".join(p for p in lines if p)


def check_attachments(msg):
    attachment_notes = []
    if msg.is_multipart():
        for part in msg.walk():
            content_disposition = str(part.get_content_disposition())
            if "attachment" in content_disposition:
                filename = part.get_filename()
                if filename:
                    ext = filename.split('.')[-1].upper()
                    attachment_notes.append(f"[{ext} Attached]")
    return " ".join(set(attachment_notes))


def ask_hermes_to_summarize(email_text):
    system_prompt = (
        "You are Hermes, a brilliant AI assistant. You are summarizing emails for a user's heads-up display glasses. "
        "Provide a crisp, direct summary in 1 or 2 short sentences max. Strip out corporate fluff, email signatures, and mobile headers. "
        "Focus purely on the actionable core message or notification."
    )
    payload = {
        "model": MODEL_NAME,
        "prompt": f"{system_prompt}\n\nEmail Content:\n{email_text}",
        "stream": False
    }
    try:
        response = requests.post(OLLAMA_URL, json=payload, timeout=90)
        if response.status_code == 200:
            return response.json().get("response", "").strip()
        else:
            return f"[Error from Ollama: Status {response.status_code}]"
    except Exception as e:
        return f"[Failed to connect to local Hermes AI engine: {e}]"


def process_email_id(mail, mail_id):
    status, data = mail.fetch(mail_id, '(RFC822)')
    if status != "OK":
        return

    for response_part in data:
        if not isinstance(response_part, tuple):
            continue

        msg = email.message_from_bytes(response_part[1])

        subject, encoding = decode_header(msg["Subject"])[0]
        if isinstance(subject, bytes):
            subject = subject.decode(encoding or "utf-8")

        body = ""
        if msg.is_multipart():
            for part in msg.walk():
                content_type = part.get_content_type()
                if content_type == "text/plain" and "attachment" not in str(part.get_content_disposition()):
                    body += part.get_payload(decode=True).decode("utf-8", errors="ignore")
                elif content_type == "text/html" and "attachment" not in str(part.get_content_disposition()):
                    html_content = part.get_payload(decode=True).decode("utf-8", errors="ignore")
                    body += clean_email_body(html_content)
        else:
            body = msg.get_payload(decode=True).decode("utf-8", errors="ignore")

        attachments = check_attachments(msg)
        clean_text = f"Subj: {subject}\n\n{body.strip()}"
        if attachments:
            clean_text = f"{attachments}\n{clean_text}"

        print(f"\n--- NEW EMAIL: {subject} ---")
        print("🧠 Summarizing...")
        ai_summary = ask_hermes_to_summarize(clean_text)

        with summary_lock:
            email_summaries.appendleft({"subject": subject, "summary": ai_summary})

        print(f"✨ {subject}: {ai_summary}\n")

        PROCESSED_EMAIL_IDS.add(mail_id)
        mail.store(mail_id, '+FLAGS', '\\Seen')


def fetch_new_emails():
    try:
        mail = imaplib.IMAP4_SSL("imap.gmail.com")
        mail.login(GMAIL_USER, GMAIL_APP_PASS)
        mail.select("INBOX")

        status, messages = mail.search(None, 'ALL')
        if status != "OK" or not messages[0]:
            mail.logout()
            return

        mail_ids = messages[0].split()
        if not mail_ids:
            mail.logout()
            return

        # On first run grab up to 5 recent emails; after that just new ones
        if not PROCESSED_EMAIL_IDS:
            candidates = mail_ids[-5:]
        else:
            candidates = [mid for mid in mail_ids if mid not in PROCESSED_EMAIL_IDS]

        for mail_id in candidates:
            if mail_id not in PROCESSED_EMAIL_IDS:
                process_email_id(mail, mail_id)

        mail.logout()

    except Exception as e:
        print(f"Error checking mail: {e}")


if __name__ == "__main__":
    api_thread = threading.Thread(target=start_api_server, daemon=True)
    api_thread.start()
    print("Hermes Email Bridge Active. API on :5050. Monitoring inbox...")
    while True:
        fetch_new_emails()
        time.sleep(10)
