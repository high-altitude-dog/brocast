# The prototype stream server in a container, for tests on a shared machine. Standard library only, so no ffmpeg:
# use a click track. Run it with the limits in README.md, so it stays apart from anything else on the host.
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1
COPY beachsync_server.py /app/
RUN chmod 755 /app && chmod 644 /app/beachsync_server.py
USER 65534:65534
EXPOSE 8765
ENTRYPOINT ["python", "-u", "/app/beachsync_server.py", "--port", "8765"]
CMD ["--clicks-split"]
