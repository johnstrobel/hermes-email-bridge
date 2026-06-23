FROM python:3.11-slim
WORKDIR /app
RUN pip install --no-cache-dir beautifulsoup4 requests
COPY app.py .
CMD ["python", "-u", "app.py"]
